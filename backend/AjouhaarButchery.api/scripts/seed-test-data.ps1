param(
    [Parameter(Mandatory = $true)]
    [string] $BaseUrl,

    [switch] $ConfirmTestProject
)

if (-not $ConfirmTestProject) {
    throw "This script writes sample records. Re-run with -ConfirmTestProject only when BaseUrl points to your test API/Firebase project."
}

$tokenSecure = Read-Host "Paste a Firebase ID token for an active Manager account" -AsSecureString
$tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($tokenSecure)
try {
    $idToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
}

$headers = @{ Authorization = "Bearer $idToken" }
$apiBase = $BaseUrl.TrimEnd('/')
$runId = [guid]::NewGuid().ToString('N')
$staffRecords = @()
for ($index = 1; $index -le 10; $index++) {
    $uid = "sample-staff-$('{0:D2}' -f $index)"
    $profile = @{
        fullName = "Sample Cashier $index"
        email = "sample-cashier-$index@example.invalid"
        role = 'Cashier'
        isActive = $false
    } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "$apiBase/api/staff/$uid" -Headers $headers -ContentType 'application/json' -Body $profile | Out-Null
    $staffRecords += $uid
}
$products = @(
    @{ name = 'Beef Mince'; category = 'Beef'; pricePerKg = 120.00; costPerKg = 85.00; stockKg = 40.0; reorderLevelKg = 10.0 },
    @{ name = 'Beef Stew'; category = 'Beef'; pricePerKg = 110.00; costPerKg = 78.00; stockKg = 35.0; reorderLevelKg = 8.0 },
    @{ name = 'Beef Sausage'; category = 'Beef'; pricePerKg = 135.00; costPerKg = 92.00; stockKg = 28.0; reorderLevelKg = 7.0 },
    @{ name = 'Chicken Breast'; category = 'Chicken'; pricePerKg = 75.00; costPerKg = 52.00; stockKg = 25.0; reorderLevelKg = 8.0 },
    @{ name = 'Chicken Thighs'; category = 'Chicken'; pricePerKg = 68.00; costPerKg = 47.00; stockKg = 30.0; reorderLevelKg = 8.0 },
    @{ name = 'Whole Chicken'; category = 'Chicken'; pricePerKg = 62.00; costPerKg = 43.00; stockKg = 24.0; reorderLevelKg = 6.0 },
    @{ name = 'Lamb Chops'; category = 'Lamb'; pricePerKg = 155.00; costPerKg = 112.00; stockKg = 20.0; reorderLevelKg = 5.0 },
    @{ name = 'Lamb Stew'; category = 'Lamb'; pricePerKg = 125.00; costPerKg = 90.00; stockKg = 26.0; reorderLevelKg = 6.0 },
    @{ name = 'Beef Ribs'; category = 'Beef'; pricePerKg = 145.00; costPerKg = 101.00; stockKg = 18.0; reorderLevelKg = 5.0 },
    @{ name = 'Chicken Wings'; category = 'Chicken'; pricePerKg = 82.00; costPerKg = 57.00; stockKg = 22.0; reorderLevelKg = 6.0 }
)

$createdProducts = @()
foreach ($product in $products) {
    $body = $product | ConvertTo-Json
    $createdProducts += Invoke-RestMethod -Method Post -Uri "$apiBase/api/products" -Headers $headers -ContentType 'application/json' -Body $body
}

$methods = @('Cash', 'Card', 'EFT')
for ($index = 0; $index -lt $createdProducts.Count; $index++) {
    $product = $createdProducts[$index]
    $sale = @{
        customerName = "Task 2 sample customer $($index + 1)"
        customerPhone = ''
        paymentMethod = $methods[$index % $methods.Count]
        items = @(@{ productId = $product.id; quantityKg = 0.25 })
    } | ConvertTo-Json -Depth 5
    $saleHeaders = @{
        Authorization = "Bearer $idToken"
        'Idempotency-Key' = "task2-$runId-sale-$index"
    }
    Invoke-RestMethod -Method Post -Uri "$apiBase/api/sales" -Headers $saleHeaders -ContentType 'application/json' -Body $sale | Out-Null
}

Write-Host "Created $($staffRecords.Count) inactive sample staff profiles, $($createdProducts.Count) test products, and $($createdProducts.Count) test sales. Each sale also creates a sale item and stock movement. Run ID: $runId"
Write-Host "Re-running creates another sample batch. Remove these test records in Firestore when you no longer need them."
