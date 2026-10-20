namespace AjouhaarButchery.Api.DTOs;

public sealed record ProductResponse(
    string Id,
    string Name,
    string Category,
    string Unit,
    double PricePerKg,
    double? CostPerKg,
    double StockKg,
    double ReorderLevelKg,
    bool IsActive,
    DateTime CreatedAtUtc,
    DateTime UpdatedAtUtc);

public sealed class ProductRequest
{
    public string Name { get; init; } = "";
    public string Category { get; init; } = "";
    public double PricePerKg { get; init; }
    public double CostPerKg { get; init; }
    public double StockKg { get; init; }
    public double ReorderLevelKg { get; init; }
}

public sealed class ProductStatusRequest
{
    public bool IsActive { get; init; }
}

public sealed class StockAdjustmentRequest
{
    public string ProductId { get; init; } = "";
    public double QuantityChangeKg { get; init; }
    public string Reason { get; init; } = "";
    public string Notes { get; init; } = "";
}
