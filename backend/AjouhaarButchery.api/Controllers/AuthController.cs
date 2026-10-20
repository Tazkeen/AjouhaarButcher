using System.Security.Cryptography;
using System.Text.RegularExpressions;
using AjouhaarButchery.Api.DTOs;
using FirebaseAdmin.Auth;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/auth")]
[AllowAnonymous]
public sealed class AuthController(FirestoreDb firestore, FirebaseAuth firebaseAuth) : ControllerBase
{
    [HttpPost("login")]
    [EnableRateLimiting("staff-login")]
    public async Task<IActionResult> Login([FromBody] StaffLoginRequest request, CancellationToken cancellationToken)
    {
        var username = request.Username.Trim().ToLowerInvariant();
        if (!Regex.IsMatch(username, "^[a-z0-9._-]{3,32}$") || request.Password.Length == 0)
            return Unauthorized(new { message = "Username or password is incorrect." });

        var profiles = await firestore.Collection("staffUsers").WhereEqualTo("username", username).Limit(1).GetSnapshotAsync(cancellationToken);
        if (profiles.Count == 0) return Unauthorized(new { message = "Username or password is incorrect." });
        var profile = profiles.Documents[0];
        var fields = profile.ToDictionary();
        if (!fields.TryGetValue("isActive", out var active) || active is not true)
            return Unauthorized(new { message = "This staff account is inactive. Ask the manager for help." });

        try
        {
            var salt = Convert.FromBase64String(fields.GetValueOrDefault("passwordSalt") as string ?? "");
            var expected = Convert.FromBase64String(fields.GetValueOrDefault("passwordHash") as string ?? "");
            var iterations = Convert.ToInt32(fields.GetValueOrDefault("passwordIterations") ?? 0);
            if (salt.Length < 16 || expected.Length != 32 || iterations < 100_000 || iterations > 1_000_000)
                return StatusCode(500, new { message = "This account's authentication record needs manager repair." });
            var actual = Rfc2898DeriveBytes.Pbkdf2(request.Password, salt, iterations, HashAlgorithmName.SHA256, expected.Length);
            if (!CryptographicOperations.FixedTimeEquals(actual, expected))
                return Unauthorized(new { message = "Username or password is incorrect." });

            var uid = fields.GetValueOrDefault("authUid") as string;
            if (string.IsNullOrWhiteSpace(uid)) uid = profile.Id;
            var customToken = await firebaseAuth.CreateCustomTokenAsync(uid, cancellationToken);
            return Ok(new
            {
                customToken,
                uid,
                username,
                fullName = fields.GetValueOrDefault("fullName") as string ?? username,
                role = fields.GetValueOrDefault("role") as string ?? "Cashier"
            });
        }
        catch (FormatException)
        {
            return StatusCode(500, new { message = "This account's authentication record needs manager repair." });
        }
    }
}
