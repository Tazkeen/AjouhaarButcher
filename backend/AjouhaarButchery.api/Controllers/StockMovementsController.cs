using AjouhaarButchery.Api.DTOs;
using AjouhaarButchery.Api.Services;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/stock-movements")]
[Authorize(Policy = "Manager")]
public sealed class StockMovementsController(FirestoreDb firestore) : ControllerBase
{
    private static readonly HashSet<string> AllowedReasons = new(StringComparer.OrdinalIgnoreCase)
        { "OpeningStock", "Correction", "Return", "Damage", "Other" };

    [HttpPost]
    public async Task<ActionResult<StockMovementResponse>> AdjustStock(
        [FromBody] StockAdjustmentRequest request,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.ProductId)) return BadRequest(new { message = "ProductId is required." });
        if (!double.IsFinite(request.QuantityChangeKg) || request.QuantityChangeKg == 0)
            return BadRequest(new { message = "QuantityChangeKg must be a finite non-zero number." });
        if (!AllowedReasons.Contains(request.Reason))
            return BadRequest(new { message = "Reason must be OpeningStock, Correction, Return, Damage, or Other." });
        if ((request.Notes?.Length ?? 0) > 300) return BadRequest(new { message = "Notes must be 300 characters or fewer." });

        var productRef = firestore.Collection("products").Document(request.ProductId);
        var movementRef = firestore.Collection("stockMovements").Document();
        var staffUid = User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)!.Value;
        var now = Timestamp.GetCurrentTimestamp();
        (bool Found, double Stock, string ProductName) outcome;
        try
        {
            outcome = await firestore.RunTransactionAsync(async transaction =>
            {
                var productSnapshot = await transaction.GetSnapshotAsync(productRef, cancellationToken);
                if (!productSnapshot.Exists) return (Found: false, Stock: 0d, ProductName: "");
                var product = productSnapshot.ToDictionary();
                var previousStock = FirestoreValues.Number(product, "stockKg", $"products/{request.ProductId}");
                var updatedStock = Math.Round(previousStock + request.QuantityChangeKg, 3, MidpointRounding.AwayFromZero);
                if (updatedStock < 0) throw new StockValidationException("Stock adjustment would make stock negative.");
                var productName = FirestoreValues.Text(product, "name", "Unnamed product");
                transaction.Update(productRef, new Dictionary<string, object>
                {
                    ["stockKg"] = updatedStock, ["updatedAtUtc"] = now
                });
                transaction.Create(movementRef, new Dictionary<string, object>
                {
                    ["productId"] = request.ProductId, ["productName"] = productName,
                    ["quantityChangeKg"] = request.QuantityChangeKg,
                    ["reason"] = NormalizeReason(request.Reason), ["staffUid"] = staffUid,
                    ["saleId"] = "", ["notes"] = (request.Notes ?? "").Trim(), ["createdAtUtc"] = now
                });
                return (Found: true, Stock: updatedStock, ProductName: productName);
            }, cancellationToken: cancellationToken);
        }
        catch (StockValidationException exception)
        {
            return Conflict(new { message = exception.Message });
        }

        if (!outcome.Found) return NotFound(new { message = "Product not found." });
        return CreatedAtAction(nameof(GetMovements), new { productId = request.ProductId }, new
        {
            productId = request.ProductId,
            productName = outcome.ProductName,
            stockKg = outcome.Stock,
            quantityChangeKg = request.QuantityChangeKg,
            reason = NormalizeReason(request.Reason)
        });
    }

    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<StockMovementResponse>>> GetMovements(
        [FromQuery] string? productId,
        [FromQuery] int limit = 100,
        CancellationToken cancellationToken = default)
    {
        if (limit is < 1 or > 500) return BadRequest(new { message = "Limit must be between 1 and 500." });
        Query query = firestore.Collection("stockMovements");
        if (!string.IsNullOrWhiteSpace(productId)) query = query.WhereEqualTo("productId", productId);
        var snapshot = await query.GetSnapshotAsync(cancellationToken);
        var result = snapshot.Documents
            .Select(document =>
            {
                var f = document.ToDictionary();
                return new StockMovementResponse(document.Id,
                    FirestoreValues.Text(f, "productId"), FirestoreValues.Text(f, "productName"),
                    FirestoreValues.Number(f, "quantityChangeKg", $"stockMovements/{document.Id}"),
                    FirestoreValues.Text(f, "reason"), FirestoreValues.Text(f, "staffUid"),
                    FirestoreValues.Text(f, "saleId") is { Length: > 0 } saleId ? saleId : null,
                    FirestoreValues.Text(f, "notes"), FirestoreValues.UtcDate(f, "createdAtUtc"));
            })
            .OrderByDescending(movement => movement.CreatedAtUtc)
            .Take(limit)
            .ToList();
        return Ok(result);
    }

    private static string NormalizeReason(string reason) => reason switch
    {
        var value when value.Equals("openingstock", StringComparison.OrdinalIgnoreCase) => "OpeningStock",
        var value when value.Equals("correction", StringComparison.OrdinalIgnoreCase) => "Correction",
        var value when value.Equals("return", StringComparison.OrdinalIgnoreCase) => "Return",
        var value when value.Equals("damage", StringComparison.OrdinalIgnoreCase) => "Damage",
        _ => "Other"
    };
}

public sealed class StockValidationException(string message) : Exception(message);
