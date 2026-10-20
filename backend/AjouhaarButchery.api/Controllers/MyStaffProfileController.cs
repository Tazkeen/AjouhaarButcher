using System.Security.Claims;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/staff/me")]
[Authorize(Policy = "Staff")]
public sealed class MyStaffProfileController(FirestoreDb firestore) : ControllerBase
{
    [HttpGet]
    public async Task<IActionResult> GetMine(CancellationToken cancellationToken)
    {
        var uid = User.FindFirstValue(ClaimTypes.NameIdentifier)!;
        var snapshot = await firestore.Collection("staffUsers").Document(uid).GetSnapshotAsync(cancellationToken);
        if (!snapshot.Exists) return NotFound(new { message = "No staff profile is set up for this account." });
        var fields = snapshot.ToDictionary();
        if (!fields.TryGetValue("isActive", out var active) || active is not true) return Forbid();
        return Ok(new
        {
            uid,
            username = Text(fields, "username"),
            fullName = Text(fields, "fullName"),
            email = Text(fields, "email"),
            role = Text(fields, "role"),
            isActive = true
        });
    }

    private static string Text(IReadOnlyDictionary<string, object> fields, string key) =>
        fields.TryGetValue(key, out var value) && value is string text ? text : "";
}
