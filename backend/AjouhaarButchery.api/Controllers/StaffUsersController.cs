using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using AjouhaarButchery.Api.DTOs;
using FirebaseAdmin.Auth;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/staff")]
[Authorize(Policy = "Manager")]
public sealed class StaffUsersController(FirestoreDb firestore, FirebaseAuth firebaseAuth) : ControllerBase
{
    private const int PasswordIterations = 310_000;

    [HttpGet]
    public async Task<IActionResult> GetStaff(CancellationToken cancellationToken)
    {
        var snapshot = await firestore.Collection("staffUsers").GetSnapshotAsync(cancellationToken);
        return Ok(snapshot.Documents.Select(document =>
        {
            var fields = document.ToDictionary();
            return new
            {
                uid = document.Id,
                username = Text(fields, "username"),
                fullName = Text(fields, "fullName"),
                googleEmail = Text(fields, "googleEmail"),
                role = Text(fields, "role"),
                isActive = Flag(fields, "isActive")
            };
        }).OrderBy(item => item.fullName).ToList());
    }

    [HttpPost]
    public async Task<IActionResult> AddStaff([FromBody] StaffCreateRequest request, CancellationToken cancellationToken)
    {
        var username = NormalizeUsername(request.Username);
        if (!Regex.IsMatch(username, "^[a-z0-9._-]{3,32}$")) return BadRequest(new { message = "Username must be 3–32 letters or numbers (dot, underscore, and dash are allowed)." });
        if (request.FullName.Trim().Length is < 2 or > 100) return BadRequest(new { message = "Enter a full name (2–100 characters)." });
        if (request.Password.Length < 4 || request.Password.Length > 128) return BadRequest(new { message = "Password must contain at least 4 characters." });
        var role = NormalizeRole(request.Role);
        if (role is null) return BadRequest(new { message = "Role must be Cashier or Manager." });
        var googleEmail = request.GoogleEmail.Trim().ToLowerInvariant();
        if (googleEmail.Length > 254 || (googleEmail.Length > 0 && !googleEmail.Contains('@'))) return BadRequest(new { message = "Enter a valid Google email, or leave it blank." });

        var collection = firestore.Collection("staffUsers");
        var existing = await collection.WhereEqualTo("username", username).Limit(1).GetSnapshotAsync(cancellationToken);
        if (existing.Count > 0) return Conflict(new { message = "That username is already in use." });
        if (googleEmail.Length > 0)
        {
            var linked = await collection.WhereEqualTo("googleEmail", googleEmail).Limit(1).GetSnapshotAsync(cancellationToken);
            if (linked.Count > 0) return Conflict(new { message = "That Google account is already linked to a staff member." });
        }

        var uid = Guid.NewGuid().ToString("N");
        var salt = RandomNumberGenerator.GetBytes(16);
        var hash = Rfc2898DeriveBytes.Pbkdf2(request.Password, salt, PasswordIterations, HashAlgorithmName.SHA256, 32);
        var values = new Dictionary<string, object>
        {
            ["username"] = username,
            ["fullName"] = request.FullName.Trim(),
            ["email"] = "",
            ["googleEmail"] = googleEmail,
            ["role"] = role,
            ["isActive"] = true,
            ["passwordSalt"] = Convert.ToBase64String(salt),
            ["passwordHash"] = Convert.ToBase64String(hash),
            ["passwordIterations"] = PasswordIterations,
            ["createdAtUtc"] = Timestamp.GetCurrentTimestamp()
        };
        await collection.Document(uid).CreateAsync(values, cancellationToken);
        return Created($"/api/staff/{uid}", new { uid, username, fullName = request.FullName.Trim(), role, isActive = true });
    }

    [HttpPut("{uid}")]
    public async Task<IActionResult> UpdateStaff(string uid, [FromBody] StaffUpdateRequest request, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(uid) || uid.Contains('/')) return BadRequest(new { message = "A valid staff account is required." });
        if (request.FullName.Trim().Length is < 2 or > 100) return BadRequest(new { message = "Enter a full name (2–100 characters)." });
        var role = NormalizeRole(request.Role);
        if (role is null) return BadRequest(new { message = "Role must be Cashier or Manager." });
        var document = firestore.Collection("staffUsers").Document(uid);
        var snapshot = await document.GetSnapshotAsync(cancellationToken);
        if (!snapshot.Exists) return NotFound(new { message = "Staff account not found." });
        if (uid == User.FindFirstValue(ClaimTypes.NameIdentifier) && (!request.IsActive || role != "Manager"))
            return BadRequest(new { message = "You cannot deactivate or demote your own manager account." });

        var values = new Dictionary<string, object>
        {
            ["fullName"] = request.FullName.Trim(),
            ["role"] = role,
            ["isActive"] = request.IsActive,
            ["googleEmail"] = request.GoogleEmail.Trim().ToLowerInvariant()
        };
        await document.SetAsync(values, SetOptions.MergeAll, cancellationToken);
        return NoContent();
    }

    [HttpDelete("{uid}")]
    public async Task<IActionResult> RemoveStaff(string uid, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(uid) || uid.Contains('/')) return BadRequest(new { message = "A valid staff account is required." });
        if (uid == User.FindFirstValue(ClaimTypes.NameIdentifier)) return BadRequest(new { message = "You cannot remove your own manager account." });
        var document = firestore.Collection("staffUsers").Document(uid);
        var snapshot = await document.GetSnapshotAsync(cancellationToken);
        if (!snapshot.Exists) return NotFound(new { message = "Staff account not found." });
        var wasManager = string.Equals(Text(snapshot.ToDictionary(), "role"), "Manager", StringComparison.OrdinalIgnoreCase);
        if (wasManager)
        {
            var staff = await firestore.Collection("staffUsers").GetSnapshotAsync(cancellationToken);
            if (staff.Documents.Count(d => string.Equals(Text(d.ToDictionary(), "role"), "Manager", StringComparison.OrdinalIgnoreCase) && Flag(d.ToDictionary(), "isActive")) <= 1)
                return Conflict(new { message = "The last active manager cannot be removed." });
        }
        await document.DeleteAsync(cancellationToken: cancellationToken);
        try { await firebaseAuth.DeleteUserAsync(uid, cancellationToken); }
        catch (FirebaseAuthException error) when (error.AuthErrorCode == AuthErrorCode.UserNotFound) { }
        return NoContent();
    }

    private static string NormalizeUsername(string value) => value.Trim().ToLowerInvariant();
    private static string? NormalizeRole(string value) => value.Trim().Equals("Manager", StringComparison.OrdinalIgnoreCase) ? "Manager" :
        value.Trim().Equals("Cashier", StringComparison.OrdinalIgnoreCase) ? "Cashier" : null;
    private static string Text(IReadOnlyDictionary<string, object> fields, string key) => fields.TryGetValue(key, out var value) && value is string text ? text : "";
    private static bool Flag(IReadOnlyDictionary<string, object> fields, string key) => fields.TryGetValue(key, out var value) && value is true;
}
