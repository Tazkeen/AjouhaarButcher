namespace AjouhaarButchery.Api.DTOs;

public sealed class CreateSaleRequest
{
    public string CustomerName { get; init; } = "";
    public string CustomerPhone { get; init; } = "";
    public string PaymentMethod { get; init; } = "";
    public double? AmountPaid { get; init; }
    public List<CreateSaleItemRequest> Items { get; init; } = [];
}

public sealed class CreateSaleItemRequest
{
    public string ProductId { get; init; } = "";
    public double QuantityKg { get; init; }
}

public sealed record SaleLineResponse(
    string ProductId,
    string ProductName,
    double QuantityKg,
    double UnitPricePerKg,
    double LineTotal);

public sealed record SaleResponse(
    string Id,
    string ReceiptNumber,
    string CashierUid,
    string CashierName,
    string CustomerName,
    string CustomerPhone,
    string PaymentMethod,
    double TotalAmount,
    double AmountPaid,
    double ChangeAmount,
    string Status,
    DateTime SaleDateUtc,
    IReadOnlyList<SaleLineResponse> Items);

public sealed record PagedSalesResponse(
    IReadOnlyList<SaleResponse> Items,
    int Page,
    int PageSize,
    int TotalCount);

public sealed record StockMovementResponse(
    string Id,
    string ProductId,
    string ProductName,
    double QuantityChangeKg,
    string Reason,
    string StaffUid,
    string? SaleId,
    string Notes,
    DateTime CreatedAtUtc);

public sealed record DailySalesResponse(
    string Date,
    int TransactionCount,
    double TotalAmount,
    IReadOnlyDictionary<string, double> TotalsByPaymentMethod);

public sealed record StockLevelResponse(
    string ProductId,
    string ProductName,
    double StockKg,
    double ReorderLevelKg,
    string Status);

public sealed class StaffProfileRequest
{
    public string FullName { get; init; } = "";
    public string Email { get; init; } = "";
    public string Role { get; init; } = "Cashier";
    public bool IsActive { get; init; } = true;
}

public sealed class StaffCreateRequest
{
    public string Username { get; init; } = "";
    public string FullName { get; init; } = "";
    public string Password { get; init; } = "";
    public string Role { get; init; } = "Cashier";
    public string GoogleEmail { get; init; } = "";
}

public sealed class StaffLoginRequest
{
    public string Username { get; init; } = "";
    public string Password { get; init; } = "";
}

public sealed class StaffUpdateRequest
{
    public string FullName { get; init; } = "";
    public string Role { get; init; } = "Cashier";
    public string GoogleEmail { get; init; } = "";
    public bool IsActive { get; init; } = true;
}
