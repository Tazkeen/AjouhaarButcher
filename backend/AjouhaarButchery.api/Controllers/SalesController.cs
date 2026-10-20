using System.Globalization;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AjouhaarButchery.Api.DTOs;
using AjouhaarButchery.Api.Services;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/sales")]
[Authorize(Policy = "Staff")]
public sealed class SalesController(FirestoreDb firestore) : ControllerBase
{
    private static readonly string[] PaymentMethods = ["Cash", "Card", "EFT"];
    private static readonly TimeZoneInfo BusinessTimeZone = GetBusinessTimeZone();

    [HttpPost]
    public async Task<ActionResult<SaleResponse>> CreateSale([FromBody] CreateSaleRequest request, CancellationToken cancellationToken)
    {
        var validation = Validate(request);
        if (validation is not null) return BadRequest(new { message = validation });

        var idempotencyKey = Request.Headers["Idempotency-Key"].FirstOrDefault();
        if (string.IsNullOrWhiteSpace(idempotencyKey)) idempotencyKey = Guid.NewGuid().ToString("N");
        if (idempotencyKey.Length > 128 || idempotencyKey.Contains('/'))
            return BadRequest(new { message = "Idempotency-Key must be 128 characters or fewer and cannot contain '/'." });
        var requestHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(request))));
        var saleId = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(idempotencyKey))).ToLowerInvariant();
        var saleRef = firestore.Collection("sales").Document(saleId);
        var staffUid = User.FindFirstValue(ClaimTypes.NameIdentifier)!;
        var staffName = User.Identity?.Name ?? staffUid;
        var normalizedItems = request.Items
            .GroupBy(item => item.ProductId.Trim(), StringComparer.Ordinal)
            .Select(group => new { ProductId = group.Key, QuantityKg = group.Sum(item => item.QuantityKg) })
            .ToList();
        var now = Timestamp.GetCurrentTimestamp();

        var result = await firestore.RunTransactionAsync(async transaction =>
        {
            var existingSale = await transaction.GetSnapshotAsync(saleRef, cancellationToken);
            if (existingSale.Exists)
            {
                var existingFields = existingSale.ToDictionary();
                var existingHash = FirestoreValues.Text(existingFields, "idempotencyHash");
                return existingHash == requestHash
                    ? SaleWriteResult.AlreadyCompleted()
                    : SaleWriteResult.Conflict("That Idempotency-Key was already used for a different sale.");
            }

            var productRefs = normalizedItems.ToDictionary(
                item => item.ProductId,
                item => firestore.Collection("products").Document(item.ProductId),
                StringComparer.Ordinal);
            var snapshots = new Dictionary<string, DocumentSnapshot>(StringComparer.Ordinal);
            foreach (var (productId, productRef) in productRefs)
                snapshots[productId] = await transaction.GetSnapshotAsync(productRef, cancellationToken);

            var lines = new List<SaleLineResponse>();
            decimal total = 0;
            foreach (var item in normalizedItems)
            {
                var productSnapshot = snapshots[item.ProductId];
                if (!productSnapshot.Exists) return SaleWriteResult.NotFound($"Product '{item.ProductId}' was not found.");
                var product = productSnapshot.ToDictionary();
                if (!FirestoreValues.Boolean(product, "isActive", true))
                    return SaleWriteResult.Invalid($"Product '{item.ProductId}' is inactive.");
                var stockKg = FirestoreValues.Number(product, "stockKg", $"products/{item.ProductId}");
                if (stockKg + 0.0000001 < item.QuantityKg)
                    return SaleWriteResult.Invalid($"Insufficient stock for {FirestoreValues.Text(product, "name", item.ProductId)}.");
                var unitPriceValue = FirestoreValues.Number(product, "pricePerKg", $"products/{item.ProductId}");
                if (unitPriceValue > (double)decimal.MaxValue)
                    return SaleWriteResult.Invalid($"Price for product '{item.ProductId}' is outside the supported range.");
                var unitPrice = Convert.ToDecimal(unitPriceValue, CultureInfo.InvariantCulture);
                var lineTotal = decimal.Round(unitPrice * Convert.ToDecimal(item.QuantityKg, CultureInfo.InvariantCulture), 2, MidpointRounding.AwayFromZero);
                total += lineTotal;
                lines.Add(new SaleLineResponse(item.ProductId, FirestoreValues.Text(product, "name", item.ProductId),
                    Math.Round(item.QuantityKg, 3, MidpointRounding.AwayFromZero), (double)unitPrice, (double)lineTotal));
            }

            var totalRounded = decimal.Round(total, 2, MidpointRounding.AwayFromZero);
            var amountPaid = request.AmountPaid is null ? totalRounded : Convert.ToDecimal(request.AmountPaid.Value, CultureInfo.InvariantCulture);
            if (amountPaid < totalRounded) return SaleWriteResult.Invalid("Amount paid is less than the sale total.");
            amountPaid = decimal.Round(amountPaid, 2, MidpointRounding.AwayFromZero);
            var changeAmount = amountPaid - totalRounded;
            var receiptNumber = $"RCP-{now.ToDateTime().ToString("yyyyMMdd", CultureInfo.InvariantCulture)}-{saleId[..6].ToUpperInvariant()}";
            transaction.Create(saleRef, new Dictionary<string, object>
            {
                ["receiptNumber"] = receiptNumber,
                ["idempotencyHash"] = requestHash,
                ["cashierUid"] = staffUid,
                ["cashierName"] = staffName,
                ["customerName"] = request.CustomerName.Trim(),
                ["customerPhone"] = request.CustomerPhone.Trim(),
                ["paymentMethod"] = NormalizePaymentMethod(request.PaymentMethod),
                ["totalAmount"] = (double)totalRounded,
                ["amountPaid"] = (double)amountPaid,
                ["changeAmount"] = (double)changeAmount,
                ["status"] = "Completed",
                ["saleDateUtc"] = now,
                ["createdAtUtc"] = now
            });

            foreach (var line in lines)
            {
                var productRef = productRefs[line.ProductId];
                var productFields = snapshots[line.ProductId].ToDictionary();
                var newStock = FirestoreValues.Number(productFields, "stockKg", $"products/{line.ProductId}") - line.QuantityKg;
                var movementRef = firestore.Collection("stockMovements").Document();
                transaction.Update(productRef, new Dictionary<string, object>
                {
                    ["stockKg"] = Math.Round(newStock, 3, MidpointRounding.AwayFromZero),
                    ["updatedAtUtc"] = now
                });
                transaction.Create(saleRef.Collection("items").Document(line.ProductId), new Dictionary<string, object>
                {
                    ["productId"] = line.ProductId,
                    ["productName"] = line.ProductName,
                    ["quantityKg"] = line.QuantityKg,
                    ["unitPricePerKg"] = line.UnitPricePerKg,
                    ["lineTotal"] = line.LineTotal
                });
                transaction.Create(movementRef, new Dictionary<string, object>
                {
                    ["productId"] = line.ProductId,
                    ["productName"] = line.ProductName,
                    ["quantityChangeKg"] = -line.QuantityKg,
                    ["reason"] = "Sale",
                    ["staffUid"] = staffUid,
                    ["saleId"] = saleId,
                    ["notes"] = $"Receipt {receiptNumber}",
                    ["createdAtUtc"] = now
                });
            }
            return SaleWriteResult.Success();
        }, cancellationToken: cancellationToken);

        if (!result.Ok)
        {
            if (result.ConflictMessage is not null) return Conflict(new { message = result.ConflictMessage });
            if (result.NotFoundMessage is not null) return NotFound(new { message = result.NotFoundMessage });
            return BadRequest(new { message = result.ErrorMessage });
        }
        var saved = await saleRef.GetSnapshotAsync(cancellationToken);
        return result.WasAlreadyCompleted
            ? Ok(await MapSale(saved, cancellationToken))
            : CreatedAtAction(nameof(GetSale), new { id = saleId }, await MapSale(saved, cancellationToken));
    }

    [HttpGet]
    public async Task<ActionResult<PagedSalesResponse>> SearchSales(
        [FromQuery] string? receiptNumber,
        [FromQuery] string? customerName,
        [FromQuery] string? cashierUid,
        [FromQuery] DateOnly? fromDate,
        [FromQuery] DateOnly? toDate,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20,
        CancellationToken cancellationToken = default)
    {
        if (page < 1 || pageSize is < 1 or > 100) return BadRequest(new { message = "Page must be positive and pageSize must be between 1 and 100." });
        if (fromDate.HasValue && toDate.HasValue && fromDate > toDate) return BadRequest(new { message = "fromDate cannot be after toDate." });
        if (!User.IsInRole("Manager")) cashierUid = User.FindFirstValue(ClaimTypes.NameIdentifier);

        var snapshot = await firestore.Collection("sales").GetSnapshotAsync(cancellationToken);
        var sales = snapshot.Documents
            .Select(document => (Document: document, Fields: document.ToDictionary()))
            .Where(item => FirestoreValues.Text(item.Fields, "status") == "Completed")
            .Where(item => string.IsNullOrWhiteSpace(cashierUid) || FirestoreValues.Text(item.Fields, "cashierUid") == cashierUid)
            .Where(item => string.IsNullOrWhiteSpace(receiptNumber) || FirestoreValues.Text(item.Fields, "receiptNumber").Contains(receiptNumber, StringComparison.OrdinalIgnoreCase))
            .Where(item => string.IsNullOrWhiteSpace(customerName) || FirestoreValues.Text(item.Fields, "customerName").Contains(customerName, StringComparison.OrdinalIgnoreCase))
            .Where(item => IsOnOrAfterBusinessDate(item.Fields, fromDate))
            .Where(item => IsOnOrBeforeBusinessDate(item.Fields, toDate))
            .OrderByDescending(item => FirestoreValues.UtcDate(item.Fields, "saleDateUtc"))
            .ToList();
        var pageDocuments = sales.Skip((page - 1) * pageSize).Take(pageSize).Select(item => item.Document).ToList();
        var pageItems = new List<SaleResponse>(pageDocuments.Count);
        foreach (var document in pageDocuments) pageItems.Add(await MapSale(document, cancellationToken));
        return Ok(new PagedSalesResponse(pageItems, page, pageSize, sales.Count));
    }

    [HttpGet("{id}")]
    public async Task<ActionResult<SaleResponse>> GetSale(string id, CancellationToken cancellationToken)
    {
        var document = await firestore.Collection("sales").Document(id).GetSnapshotAsync(cancellationToken);
        if (!document.Exists) return NotFound(new { message = "Sale not found." });
        if (!CanView(document)) return NotFound(new { message = "Sale not found." });
        return Ok(await MapSale(document, cancellationToken));
    }

    [HttpGet("{id}/receipt")]
    public async Task<ActionResult<SaleResponse>> GetReceipt(string id, CancellationToken cancellationToken)
    {
        var document = await firestore.Collection("sales").Document(id).GetSnapshotAsync(cancellationToken);
        if (!document.Exists) return NotFound(new { message = "Receipt not found." });
        if (!CanView(document)) return NotFound(new { message = "Receipt not found." });
        return Ok(await MapSale(document, cancellationToken));
    }

    private bool CanView(DocumentSnapshot document)
    {
        if (User.IsInRole("Manager")) return true;
        return document.ToDictionary().TryGetValue("cashierUid", out var uid) &&
            uid is string staffUid && staffUid == User.FindFirstValue(ClaimTypes.NameIdentifier);
    }

    private async Task<SaleResponse> MapSale(DocumentSnapshot document, CancellationToken cancellationToken)
    {
        var fields = document.ToDictionary();
        var itemSnapshot = await document.Reference.Collection("items").GetSnapshotAsync(cancellationToken);
        var items = itemSnapshot.Documents.Select(itemDocument =>
        {
            var item = itemDocument.ToDictionary();
            return new SaleLineResponse(
                FirestoreValues.Text(item, "productId", itemDocument.Id),
                FirestoreValues.Text(item, "productName"),
                FirestoreValues.Number(item, "quantityKg", $"sales/{document.Id}/items/{itemDocument.Id}"),
                FirestoreValues.Number(item, "unitPricePerKg", $"sales/{document.Id}/items/{itemDocument.Id}"),
                FirestoreValues.Number(item, "lineTotal", $"sales/{document.Id}/items/{itemDocument.Id}"));
        }).ToList();
        return new SaleResponse(document.Id,
            FirestoreValues.Text(fields, "receiptNumber"),
            FirestoreValues.Text(fields, "cashierUid"),
            FirestoreValues.Text(fields, "cashierName"),
            FirestoreValues.Text(fields, "customerName"),
            FirestoreValues.Text(fields, "customerPhone"),
            FirestoreValues.Text(fields, "paymentMethod"),
            FirestoreValues.Number(fields, "totalAmount", $"sales/{document.Id}"),
            FirestoreValues.Number(fields, "amountPaid", $"sales/{document.Id}", FirestoreValues.Number(fields, "totalAmount", $"sales/{document.Id}")),
            FirestoreValues.Number(fields, "changeAmount", $"sales/{document.Id}"),
            FirestoreValues.Text(fields, "status"),
            FirestoreValues.UtcDate(fields, "saleDateUtc"), items);
    }

    private static string? Validate(CreateSaleRequest request)
    {
        if (request.Items is null || request.Items.Count == 0 || request.Items.Count > 30) return "A sale must include between 1 and 30 product lines.";
        if (request.CustomerName.Trim().Length > 100 || request.CustomerPhone.Trim().Length > 30) return "Customer details exceed the allowed length.";
        if (!PaymentMethods.Contains(request.PaymentMethod, StringComparer.OrdinalIgnoreCase)) return "PaymentMethod must be Cash, Card, or EFT.";
        if (request.AmountPaid is double paid && (!double.IsFinite(paid) || paid < 0)) return "AmountPaid must be a finite, non-negative amount.";
        foreach (var item in request.Items)
        {
            if (string.IsNullOrWhiteSpace(item.ProductId) || item.ProductId.Contains('/')) return "Every item needs a valid ProductId.";
            if (!double.IsFinite(item.QuantityKg) || item.QuantityKg <= 0 || item.QuantityKg > 1000) return "Each quantity must be greater than 0 and no more than 1000 kg.";
        }
        return null;
    }

    private static string NormalizePaymentMethod(string value) =>
        PaymentMethods.First(method => method.Equals(value, StringComparison.OrdinalIgnoreCase));

    private static bool IsOnOrAfterBusinessDate(IReadOnlyDictionary<string, object> fields, DateOnly? date)
    {
        if (date is null) return true;
        var localDate = DateOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(FirestoreValues.UtcDate(fields, "saleDateUtc"), BusinessTimeZone));
        return localDate >= date.Value;
    }

    private static bool IsOnOrBeforeBusinessDate(IReadOnlyDictionary<string, object> fields, DateOnly? date)
    {
        if (date is null) return true;
        var localDate = DateOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(FirestoreValues.UtcDate(fields, "saleDateUtc"), BusinessTimeZone));
        return localDate <= date.Value;
    }

    private static TimeZoneInfo GetBusinessTimeZone()
    {
        try { return TimeZoneInfo.FindSystemTimeZoneById("Africa/Johannesburg"); }
        catch (TimeZoneNotFoundException) { return TimeZoneInfo.FindSystemTimeZoneById("South Africa Standard Time"); }
    }

    private sealed record SaleWriteResult(bool Ok, bool WasAlreadyCompleted, string? ErrorMessage, string? NotFoundMessage, string? ConflictMessage)
    {
        public static SaleWriteResult Success() => new(true, false, null, null, null);
        public static SaleWriteResult AlreadyCompleted() => new(true, true, null, null, null);
        public static SaleWriteResult Invalid(string message) => new(false, false, message, null, null);
        public static SaleWriteResult NotFound(string message) => new(false, false, null, message, null);
        public static SaleWriteResult Conflict(string message) => new(false, false, null, null, message);
    }
}
