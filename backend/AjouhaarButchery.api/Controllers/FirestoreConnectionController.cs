
using Google.Cloud.Firestore;
using Microsoft.AspNetCore.Mvc;

namespace AjouhaarButchery.Api.Controllers;

[ApiController]
[Route("api/connection")]
public class FirestoreConnectionController : ControllerBase
{
    private readonly FirestoreDb _firestore;

    public FirestoreConnectionController(FirestoreDb firestore)
    {
        _firestore = firestore;
    }

    [HttpGet("firestore")]
    public async Task<IActionResult> TestFirestore()
    {
        try
        {
            var snapshot = await _firestore
                .Collection("products")
                .Limit(1)
                .GetSnapshotAsync();

            return Ok(new
            {
                connected = true,
                message = "Firestore connection successful.",
                productsCollectionReadable = true,
                hasProducts = snapshot.Documents.Count > 0
            });
        }
        catch (Exception ex)
        {
            return StatusCode(500, new
            {
                connected = false,
                message = "Firestore connection failed.",
                errorType = ex.GetType().Name
            });
        }
    }
}

