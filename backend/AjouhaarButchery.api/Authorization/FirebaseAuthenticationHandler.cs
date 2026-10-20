using System.Security.Claims;
using System.Text.Encodings.Web;
using FirebaseAdmin.Auth;
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace AjouhaarButchery.Api.Authorization;

public sealed class FirebaseAuthenticationHandler : AuthenticationHandler<AuthenticationSchemeOptions>
{
    private readonly FirebaseAuth _auth;
    private readonly FirestoreDb _firestore;

    public FirebaseAuthenticationHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder,
        FirebaseAuth auth,
        FirestoreDb firestore)
        : base(options, logger, encoder)
    {
        _auth = auth;
        _firestore = firestore;
    }

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        if (!Request.Headers.TryGetValue("Authorization", out var header)) return AuthenticateResult.NoResult();
        var value = header.ToString();
        if (!value.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
            return AuthenticateResult.Fail("Use an Authorization: Bearer <Firebase ID token> header.");

        var idToken = value[7..].Trim();
        if (idToken.Length == 0) return AuthenticateResult.Fail("Firebase ID token is missing.");

        try
        {
            var token = await _auth.VerifyIdTokenAsync(idToken);
            var staff = _firestore.Collection("staffUsers");
            var profile = await staff.Document(token.Uid).GetSnapshotAsync(Context.RequestAborted);
            if (!profile.Exists && token.Claims.TryGetValue("email", out var emailClaim) && emailClaim is string googleEmail && !string.IsNullOrWhiteSpace(googleEmail))
            {
                var linked = await staff.WhereEqualTo("googleEmail", googleEmail.Trim().ToLowerInvariant()).Limit(1).GetSnapshotAsync(Context.RequestAborted);
                var linkedProfile = linked.Documents.FirstOrDefault();
                if (linkedProfile is not null) profile = linkedProfile;
            }
            if (!profile.Exists) return AuthenticateResult.Fail("No staff profile is registered for this account.");

            var fields = profile.ToDictionary();
            if (!fields.TryGetValue("isActive", out var activeValue) || activeValue is not true)
                return AuthenticateResult.Fail("This staff account is inactive.");
            if (!fields.TryGetValue("role", out var roleValue) || roleValue is not string)
                return AuthenticateResult.Fail("The staff profile has no valid role.");
            var role = (string)roleValue;
            if (!(role.Equals("Cashier", StringComparison.OrdinalIgnoreCase) || role.Equals("Manager", StringComparison.OrdinalIgnoreCase)))
                return AuthenticateResult.Fail("The staff profile has no valid role.");

            role = char.ToUpperInvariant(role[0]) + role[1..].ToLowerInvariant();
            var claims = new List<Claim>
            {
                new Claim(ClaimTypes.NameIdentifier, profile.Id),
                new Claim(ClaimTypes.Role, role)
            };
            if (fields.TryGetValue("fullName", out var name) && name is string && !string.IsNullOrWhiteSpace((string)name))
            {
                var fullName = (string)name;
                claims.Add(new Claim(ClaimTypes.Name, fullName));
            }
            if (fields.TryGetValue("email", out var email) && email is string)
            {
                var emailAddress = (string)email;
                claims.Add(new Claim(ClaimTypes.Email, emailAddress));
            }

            var identity = new ClaimsIdentity(claims, Scheme.Name);
            return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), Scheme.Name));
        }
        catch (FirebaseAuthException)
        {
            return AuthenticateResult.Fail("Firebase ID token is invalid or expired.");
        }
        catch (ArgumentException)
        {
            return AuthenticateResult.Fail("Firebase ID token is invalid.");
        }
    }
}
