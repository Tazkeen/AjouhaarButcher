
using Google.Cloud.Firestore;

namespace AjouhaarButchery.Api.Models;

[FirestoreData]
public class Product
{
    public string Id { get; set; } = "";

    [FirestoreProperty("name")]
    public string Name { get; set; } = "";

    [FirestoreProperty("category")]
    public string Category { get; set; } = "";

    [FirestoreProperty("pricePerKg")]
    public double PricePerKg { get; set; }

    [FirestoreProperty("costPerKg")]
    public double CostPerKg { get; set; }

    [FirestoreProperty("stockKg")]
    public double StockKg { get; set; }

    [FirestoreProperty("reorderLevelKg")]
    public double ReorderLevelKg { get; set; }

    [FirestoreProperty("isActive")]
    public bool IsActive { get; set; } = true;

    [FirestoreProperty("createdAtUtc")]
    public Timestamp CreatedAtUtc { get; set; }

    [FirestoreProperty("updatedAtUtc")]
    public Timestamp UpdatedAtUtc { get; set; }
}
