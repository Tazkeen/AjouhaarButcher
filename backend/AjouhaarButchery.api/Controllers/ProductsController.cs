using AjouhaarButchery.Api.DTOs;
using AjouhaarButchery.Api.Services;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/products")]
[Authorize(Policy = "Staff")]
public sealed class ProductsController(FirestoreDb firestore) : ControllerBase
{
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<ProductResponse>>> GetProducts(
        [FromQuery] bool includeInactive = false,
        CancellationToken cancellationToken = default)
    {
        if (includeInactive && !User.IsInRole("Manager")) return Forbid();
        var snapshot = await firestore.Collection("products").GetSnapshotAsync(cancellationToken);
        var products = snapshot.Documents
            .Select(MapProduct)
            .Where(product => includeInactive || product.IsActive)
            .OrderBy(product => product.Name)
            .ToList();
        return Ok(products);
    }

    [HttpGet("low-stock")]
    public async Task<ActionResult<IReadOnlyList<StockLevelResponse>>> GetLowStock(CancellationToken cancellationToken)
    {
        var snapshot = await firestore.Collection("products").GetSnapshotAsync(cancellationToken);
        var lowStock = snapshot.Documents.Select(MapProduct)
            .Where(product => product.IsActive && product.StockKg <= product.ReorderLevelKg)
            .OrderBy(product => product.StockKg)
            .Select(product => new StockLevelResponse(product.Id, product.Name, product.StockKg,
                product.ReorderLevelKg, "Low stock"))
            .ToList();
        return Ok(lowStock);
    }

    [HttpGet("{id}")]
    public async Task<ActionResult<ProductResponse>> GetProduct(string id, CancellationToken cancellationToken)
    {
        var snapshot = await firestore.Collection("products").Document(id).GetSnapshotAsync(cancellationToken);
        if (!snapshot.Exists) return NotFound(new { message = "Product not found." });
        var product = MapProduct(snapshot);
        if (!product.IsActive && !User.IsInRole("Manager")) return NotFound(new { message = "Product not found." });
        return Ok(product);
    }

    [HttpPost]
    [Authorize(Policy = "Manager")]
    public async Task<ActionResult> AddProduct([FromBody] ProductRequest request, CancellationToken cancellationToken)
    {
        var validation = Validate(request);
        if (validation is not null) return BadRequest(new { message = validation });

        var now = Timestamp.GetCurrentTimestamp();
        var document = firestore.Collection("products").Document();
        var product = new Dictionary<string, object>
        {
            ["name"] = request.Name.Trim(), ["category"] = request.Category.Trim(), ["unit"] = "kg",
            ["pricePerKg"] = request.PricePerKg, ["costPerKg"] = request.CostPerKg,
            ["stockKg"] = request.StockKg, ["reorderLevelKg"] = request.ReorderLevelKg,
            ["isActive"] = true, ["createdAtUtc"] = now, ["updatedAtUtc"] = now
        };
        await document.SetAsync(product, cancellationToken: cancellationToken);
        var response = new ProductResponse(document.Id, request.Name.Trim(), request.Category.Trim(), "kg",
            request.PricePerKg, request.CostPerKg, request.StockKg, request.ReorderLevelKg, true,
            now.ToDateTime(), now.ToDateTime());
        return CreatedAtAction(nameof(GetProduct), new { id = document.Id }, response);
    }

    [HttpPut("{id}")]
    [Authorize(Policy = "Manager")]
    public async Task<ActionResult<ProductResponse>> UpdateProduct(string id, [FromBody] ProductRequest request, CancellationToken cancellationToken)
    {
        var validation = Validate(request);
        if (validation is not null) return BadRequest(new { message = validation });
        var document = firestore.Collection("products").Document(id);
        var snapshot = await document.GetSnapshotAsync(cancellationToken);
        if (!snapshot.Exists) return NotFound(new { message = "Product not found." });
        var existing = snapshot.ToDictionary();
        var now = Timestamp.GetCurrentTimestamp();
        var update = new Dictionary<string, object>
        {
            ["name"] = request.Name.Trim(), ["category"] = request.Category.Trim(), ["unit"] = "kg",
            ["pricePerKg"] = request.PricePerKg, ["costPerKg"] = request.CostPerKg,
            ["reorderLevelKg"] = request.ReorderLevelKg, ["updatedAtUtc"] = now
        };
        await document.UpdateAsync(update, cancellationToken: cancellationToken);
        var updated = new ProductResponse(id, request.Name.Trim(), request.Category.Trim(), "kg",
            request.PricePerKg, request.CostPerKg, FirestoreValues.Number(existing, "stockKg", $"products/{id}"),
            request.ReorderLevelKg, FirestoreValues.Boolean(existing, "isActive", true),
            FirestoreValues.UtcDate(existing, "createdAtUtc"), now.ToDateTime());
        return Ok(updated);
    }

    [HttpPatch("{id}/status")]
    [Authorize(Policy = "Manager")]
    public async Task<IActionResult> SetStatus(string id, [FromBody] ProductStatusRequest request, CancellationToken cancellationToken)
    {
        var document = firestore.Collection("products").Document(id);
        var snapshot = await document.GetSnapshotAsync(cancellationToken);
        if (!snapshot.Exists) return NotFound(new { message = "Product not found." });
        await document.UpdateAsync(new Dictionary<string, object>
        {
            ["isActive"] = request.IsActive,
            ["updatedAtUtc"] = Timestamp.GetCurrentTimestamp()
        }, cancellationToken: cancellationToken);
        return NoContent();
    }

    private ProductResponse MapProduct(DocumentSnapshot document)
    {
        var fields = document.ToDictionary();
        var manager = User.IsInRole("Manager");
        return new ProductResponse(
            document.Id,
            FirestoreValues.Text(fields, "name", "Unnamed product"),
            FirestoreValues.Text(fields, "category"),
            FirestoreValues.Text(fields, "unit", "kg"),
            FirestoreValues.Number(fields, "pricePerKg", $"products/{document.Id}"),
            manager ? FirestoreValues.Number(fields, "costPerKg", $"products/{document.Id}") : null,
            FirestoreValues.Number(fields, "stockKg", $"products/{document.Id}"),
            FirestoreValues.Number(fields, "reorderLevelKg", $"products/{document.Id}"),
            FirestoreValues.Boolean(fields, "isActive", true),
            FirestoreValues.UtcDate(fields, "createdAtUtc"),
            FirestoreValues.UtcDate(fields, "updatedAtUtc"));
    }

    private static string? Validate(ProductRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Name) || request.Name.Trim().Length > 100) return "Product name is required and must be 100 characters or fewer.";
        if (string.IsNullOrWhiteSpace(request.Category) || request.Category.Trim().Length > 60) return "Category is required and must be 60 characters or fewer.";
        if (!double.IsFinite(request.PricePerKg) || request.PricePerKg <= 0) return "Price per kilogram must be a positive number.";
        if (!double.IsFinite(request.CostPerKg) || request.CostPerKg < 0) return "Cost per kilogram cannot be negative.";
        if (!double.IsFinite(request.StockKg) || request.StockKg < 0) return "Stock cannot be negative.";
        if (!double.IsFinite(request.ReorderLevelKg) || request.ReorderLevelKg < 0) return "Reorder level cannot be negative.";
        return null;
    }
}
