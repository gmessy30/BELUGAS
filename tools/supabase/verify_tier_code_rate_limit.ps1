<#
.SYNOPSIS
  Proves (not assumes) redeem_tier_code's rate limit actually behaves as designed
  (20260905010000_add_tier_code_rate_limiting_and_identity_bind.sql). Run this ONCE after
  pushing that migration, before trusting it in production. Exits non-zero (and prints FAIL
  lines) on any violation -- this is meant to go red if the throttle regresses, not just report
  a number.

  Checks, in order:
    1. 12 attempts from one fresh subscriber_id all go through (no false positives on a
       generous, legitimate burst).
    2. The 13th attempt in the same hour is refused.
    3. The refusal is a DIFFERENT outcome (HTTP status + body shape) than an invalid code from a
       fresh, never-throttled subscriber_id -- proving the client can actually distinguish
       "slow down" from "that code's wrong" rather than both collapsing to the same thing.
    4. (Only with -ServiceRoleKey) The throttle is a true sliding window keyed to elapsed time
       since each attempt, not a fixed calendar-hour bucket:
         - attempts backdated to 61 minutes ago no longer count -- a fresh attempt succeeds.
         - attempts backdated to only 59 minutes ago still count -- a 13th attempt is still
           refused. Checking BOTH sides of that boundary in the same run is what actually rules
           out a calendar-bucket implementation: a "resets at the top of the hour" bug would
           pass or fail this test depending on what wall-clock minute you happened to run it at,
           while a true sliding window (attempted_at > now() - interval '1 hour', which is what
           this migration actually writes) gives the same two results regardless of when it's
           run. Skipped, loudly, if no service-role key is given -- there is no anon-reachable
           way to backdate a row in tier_code_redeem_attempts (deliberately -- see that table's
           own comment), so this check can't run without it.

.PARAMETER SupabaseUrl
  Defaults to reading supabase.url out of local.properties (same source SupabaseSecrets.kt
  generates from at build time), so this doesn't need its own copy of the project URL.

.PARAMETER AnonKey
  Defaults to reading supabase.anonKey out of local.properties, same reasoning.

.PARAMETER ServiceRoleKey
  Settings -> API -> service_role secret in the Supabase dashboard. NEVER pass this as a plain
  string in a way that could land in shell history you keep -- prefer pasting it interactively
  or via an env var for that one run. Optional: omitting it just skips check 4 (with a clear
  SKIPPED line, not a silent pass) since backdating attempts needs to bypass RLS.

.EXAMPLE
  .\verify_tier_code_rate_limit.ps1

.EXAMPLE
  .\verify_tier_code_rate_limit.ps1 -ServiceRoleKey $env:BELUGAS_SERVICE_ROLE_KEY
#>
param(
    [string]$SupabaseUrl,
    [string]$AnonKey,
    [string]$ServiceRoleKey
)

$ErrorActionPreference = "Stop"
$script:FailCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Description)
    if ($Condition) {
        Write-Host "PASS: $Description" -ForegroundColor Green
    } else {
        Write-Host "FAIL: $Description" -ForegroundColor Red
        $script:FailCount++
    }
}

function Read-LocalProperty {
    param([string]$Key)
    $localPropsPath = Join-Path $PSScriptRoot "..\..\local.properties"
    if (-not (Test-Path $localPropsPath)) { return $null }
    $line = Get-Content $localPropsPath | Where-Object { $_ -match "^\s*$Key\s*=" } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -split "=", 2)[1].Trim()
}

if (-not $SupabaseUrl) { $SupabaseUrl = Read-LocalProperty "supabase.url" }
if (-not $AnonKey) { $AnonKey = Read-LocalProperty "supabase.anonKey" }
if (-not $SupabaseUrl -or -not $AnonKey) {
    throw "Could not resolve SupabaseUrl/AnonKey from local.properties -- pass -SupabaseUrl/-AnonKey explicitly."
}

$RestBase = "$SupabaseUrl/rest/v1"

# Raw HttpWebRequest rather than Invoke-WebRequest/Invoke-RestMethod: those cmdlets' behavior on
# a non-2xx response (throw vs. -SkipHttpErrorCheck) differs between Windows PowerShell 5.1 and
# PowerShell 7+, and this needs to read the exact status/body of a REFUSAL (400, "rate_limited")
# just as reliably as a success -- that's the whole point of the test. HttpWebRequest/WebException
# is the one HTTP surface that behaves identically on both, so there's one code path instead of
# a version-conditional one.
function Invoke-RestCall {
    param(
        [string]$Uri,
        [string]$Method,
        [string]$ApiKey,
        [string]$BodyJson,
        [string]$Prefer
    )
    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = $Method
    $request.ContentType = "application/json"
    $request.Accept = "application/json"
    $request.Headers.Add("apikey", $ApiKey)
    $request.Headers.Add("Authorization", "Bearer $ApiKey")
    if ($Prefer) { $request.Headers.Add("Prefer", $Prefer) }
    if ($BodyJson) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($BodyJson)
        $request.ContentLength = $bytes.Length
        $requestStream = $request.GetRequestStream()
        $requestStream.Write($bytes, 0, $bytes.Length)
        $requestStream.Close()
    } else {
        $request.ContentLength = 0
    }
    try {
        $response = $request.GetResponse()
    } catch [System.Net.WebException] {
        # A non-2xx status lands here, not in the try block above -- this IS the "refused" path,
        # not a script error, so its response is read exactly the same way as a success below.
        $response = $_.Exception.Response
    }
    $statusCode = [int]$response.StatusCode
    $responseStream = New-Object System.IO.StreamReader($response.GetResponseStream())
    $content = $responseStream.ReadToEnd()
    $responseStream.Close()
    $response.Close()
    return @{ Status = $statusCode; Body = $content }
}

function Invoke-RedeemAttempt {
    param([string]$SubscriberId, [string]$Code = "NOTAREALCODE")
    $body = @{ p_code = $Code; p_subscriber_id = $SubscriberId } | ConvertTo-Json
    return Invoke-RestCall -Uri "$RestBase/rpc/redeem_tier_code" -Method "POST" -ApiKey $AnonKey -BodyJson $body
}

Write-Host "`n=== 1-2: burn 12 attempts, confirm the 13th is refused ===" -ForegroundColor Cyan
$testSubscriberId = [guid]::NewGuid().ToString()
$allTwelveOk = $true
for ($i = 1; $i -le 12; $i++) {
    $result = Invoke-RedeemAttempt -SubscriberId $testSubscriberId
    if ($result.Status -ne 200) { $allTwelveOk = $false }
}
Assert-True $allTwelveOk "all 12 attempts within the first hour succeeded (HTTP 200 each)"

$thirteenth = Invoke-RedeemAttempt -SubscriberId $testSubscriberId
Assert-True ($thirteenth.Status -ne 200) "the 13th attempt in the same hour was refused (non-200)"
Assert-True ($thirteenth.Body -match "rate_limited") "the refusal's message is exactly 'rate_limited'"

Write-Host "`n=== 3: rate-limited is a distinct outcome from an invalid code ===" -ForegroundColor Cyan
$freshSubscriberId = [guid]::NewGuid().ToString()
$freshInvalid = Invoke-RedeemAttempt -SubscriberId $freshSubscriberId
Assert-True ($freshInvalid.Status -eq 200) "a fresh, never-throttled subscriber gets HTTP 200 for a bad code (not the rate-limit's error status)"
Assert-True ($freshInvalid.Body.Trim() -eq "null") "...with a plain null body -- structurally different from the rate-limited response above, not just a different message string"

if (-not $ServiceRoleKey) {
    Write-Host "`n=== 4: sliding-window release ===" -ForegroundColor Yellow
    Write-Host "SKIPPED: no -ServiceRoleKey given -- can't backdate tier_code_redeem_attempts rows without it (anon has no write access to that table, by design)." -ForegroundColor Yellow
} else {
    Write-Host "`n=== 4: sliding-window release (both sides of the 60-minute boundary) ===" -ForegroundColor Cyan

    function Set-AttemptAge {
        param([string]$SubscriberId, [int]$MinutesAgo)
        $timestamp = (Get-Date).ToUniversalTime().AddMinutes(-$MinutesAgo).ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        $body = @{ attempted_at = $timestamp } | ConvertTo-Json
        Invoke-RestCall -Uri "$RestBase/tier_code_redeem_attempts?subscriber_id=eq.$SubscriberId" `
            -Method "PATCH" -ApiKey $ServiceRoleKey -BodyJson $body -Prefer "return=minimal" | Out-Null
    }

    # 4a: backdate the 12 blocking attempts to 61 minutes ago -- outside the window, should free up.
    Set-AttemptAge -SubscriberId $testSubscriberId -MinutesAgo 61
    $afterSixtyOne = Invoke-RedeemAttempt -SubscriberId $testSubscriberId
    Assert-True ($afterSixtyOne.Status -eq 200) "attempts backdated to 61 minutes ago no longer count -- a new attempt succeeds"

    # 4b: fresh subscriber, 12 attempts backdated to 59 minutes ago -- inside the window, should
    # still block. Using a NEW subscriber_id so this isn't muddied by the one fresh attempt
    # check 4a just logged for testSubscriberId.
    $slidingSubscriberId = [guid]::NewGuid().ToString()
    for ($i = 1; $i -le 12; $i++) { Invoke-RedeemAttempt -SubscriberId $slidingSubscriberId | Out-Null }
    Set-AttemptAge -SubscriberId $slidingSubscriberId -MinutesAgo 59
    $atFiftyNine = Invoke-RedeemAttempt -SubscriberId $slidingSubscriberId
    Assert-True ($atFiftyNine.Status -ne 200) "attempts backdated to only 59 minutes ago still count -- still refused"
    Assert-True ($atFiftyNine.Body -match "rate_limited") "...and still specifically 'rate_limited', not some other failure"
}

Write-Host ""
if ($script:FailCount -gt 0) {
    Write-Host "$($script:FailCount) check(s) FAILED." -ForegroundColor Red
    exit 1
} else {
    Write-Host "All checks passed." -ForegroundColor Green
    exit 0
}
