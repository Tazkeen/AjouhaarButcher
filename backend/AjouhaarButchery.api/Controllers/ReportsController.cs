using AjouhaarButchery.Api.DTOs;
using AjouhaarButchery.Api.Services;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/reports")]
[Authorize(Policy = "Manager")]
public sealed class ReportsController(FirestoreDb firestore) : ControllerBase
{
    [HttpGet("daily-sales")]
    public async Task<ActionResult<DailySalesResponse>> GetDailySales([FromQuery] DateOnly date, CancellationToken cancellationToken)
    {
        var startLocal = date.ToDateTime(TimeOnly.MinValue, DateTimeKind.Unspecified);
        var endLocal = startLocal.AddDays(1);
        var startUtc = TimeZoneInfo.ConvertTimeToUtc(startLocal, GetBusinessTimeZone());
        var endUtc = TimeZoneInfo.ConvertTimeToUtc(endLocal, GetBusinessTimeZone());
        var snapshot = await firestore.Collection("sales").GetSnapshotAsync(cancellationToken);
        var sales = snapshot.Documents.Select(document => (Document: document, Fields: document.ToDictionary()))
            .Where(item => FirestoreValues.Text(item.Fields, "status") == "Completed")
            .Where(item =>
            {
                var timestamp = FirestoreValues.UtcDate(item.Fields, "saleDateUtc");
                return timestamp >= startUtc && timestamp < endUtc;
            }).ToList();
        var byMethod = sales.GroupBy(item => FirestoreValues.Text(item.Fields, "paymentMethod", "Unknown"))
            .ToDictionary(group => group.Key, group => Math.Round(group.Sum(item => FirestoreValues.Number(item.Fields, "totalAmount", $"sales/{item.Document.Id}")), 2));
        var total = Math.Round(sales.Sum(item => FirestoreValues.Number(item.Fields, "totalAmount", $"sales/{item.Document.Id}")), 2);
        return Ok(new DailySalesResponse(date.ToString("yyyy-MM-dd"), sales.Count, total, byMethod));
    }

    [HttpGet("stock-levels")]
    public async Task<ActionResult<IReadOnlyList<StockLevelResponse>>> GetStockLevels(CancellationToken cancellationToken)
    {
        var snapshot = await firestore.Collection("products").GetSnapshotAsync(cancellationToken);
        var result = snapshot.Documents.Select(document =>
        {
            var fields = document.ToDictionary();
            var stock = FirestoreValues.Number(fields, "stockKg", $"products/{document.Id}");
            var reorderLevel = FirestoreValues.Number(fields, "reorderLevelKg", $"products/{document.Id}");
            return new StockLevelResponse(document.Id, FirestoreValues.Text(fields, "name", "Unnamed product"),
                stock, reorderLevel, stock <= reorderLevel ? "Low stock" : "In stock");
        }).Where(product =>
        {
            var document = snapshot.Documents.First(item => item.Id == product.ProductId);
            return FirestoreValues.Boolean(document.ToDictionary(), "isActive", true);
        }).OrderBy(product => product.StockKg).ToList();
        return Ok(result);
    }

    private static TimeZoneInfo GetBusinessTimeZone()
    {
        try { return TimeZoneInfo.FindSystemTimeZoneById("Africa/Johannesburg"); }
        catch (TimeZoneNotFoundException) { return TimeZoneInfo.FindSystemTimeZoneById("South Africa Standard Time"); }
    }
}
