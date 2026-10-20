using System.Globalization;
using Google.Cloud.Firestore;

namespace AjouhaarButchery.Api.Services;

public static class FirestoreValues
{
    public static double Number(IReadOnlyDictionary<string, object> fields, string key, string documentPath, double fallback = 0)
    {
        if (!fields.TryGetValue(key, out var value) || value is null) return fallback;
        if (value is double d) return d;
        if (value is long l) return l;
        if (value is int i) return i;
        if (value is decimal m) return (double)m;
        if (value is string s && double.TryParse(s, NumberStyles.Number, CultureInfo.InvariantCulture, out d)) return d;
        throw new InvalidDataException($"Firestore field '{documentPath}/{key}' must be a number.");
    }

    public static string Text(IReadOnlyDictionary<string, object> fields, string key, string fallback = "") =>
        fields.TryGetValue(key, out var value) && value is string text ? text : fallback;

    public static bool Boolean(IReadOnlyDictionary<string, object> fields, string key, bool fallback = false) =>
        fields.TryGetValue(key, out var value) && value is bool boolean ? boolean : fallback;

    public static DateTime UtcDate(IReadOnlyDictionary<string, object> fields, string key, DateTime fallback = default)
    {
        if (!fields.TryGetValue(key, out var value) || value is null) return fallback;
        return value switch
        {
            Timestamp timestamp => timestamp.ToDateTime(),
            DateTime dateTime => DateTime.SpecifyKind(dateTime, DateTimeKind.Utc),
            _ => throw new InvalidDataException($"Firestore field '{key}' must be a timestamp.")
        };
    }
}
