<#
.SYNOPSIS
  Verifies a simplified river centerline (as produced by dp_simplify.ps1, or copied out of
  CoastlineGeometry.kt) is a strictly-ordered walk along the raw KPB path it was derived from --
  every consecutive output pair must map to strictly ascending raw-vertex indices -- and reports
  the largest consecutive-point gap (great-circle meters).

  This exists because Douglas-Peucker implementations that emit points in recursion-visit order
  rather than re-scanning the kept-flags by ascending raw index can silently produce an
  out-of-order polyline (retained points correct, sequence wrong). Run this after every
  re-simplification before pasting the result into CoastlineGeometry.kt.

.EXAMPLE
  .\verify_order.ps1 -RiverName "Kenai River" -PathIndex 2 -LiteralFile .\kenai_simplified.txt
#>
param(
    [string]$RiverName,
    [string]$AwcNo,
    [int]$PathIndex = -1,
    [Parameter(Mandatory = $true)]
    [string]$LiteralFile
)

$FeatureServerUrl = "https://services.arcgis.com/ba4DH9pIcqkXJVfl/arcgis/rest/services/KPB_2118_view/FeatureServer/0/query"

function FetchRiver {
    if (-not $RiverName -and -not $AwcNo) {
        throw "Provide -RiverName or -AwcNo (see README.md)."
    }
    $where = if ($AwcNo) { "AWC_No = '$AwcNo'" } else { "Name = '$RiverName'" }
    $resp = Invoke-RestMethod -Uri $FeatureServerUrl -Method Get -Body @{
        where          = $where
        outFields      = "OBJECTID,Name,AWC_No,Miles"
        returnGeometry = "true"
        outSR          = "4326"
        f              = "json"
    }
    if (-not $resp.features -or $resp.features.Count -eq 0) {
        throw "No feature matched ($where). Check the name/AWC_No against the layer (see README.md)."
    }
    return $resp.features[0]
}

function ToLatLng($esriPairs) {
    $n = $esriPairs.Count
    $out = New-Object 'object[]' $n
    for ($i = 0; $i -lt $n; $i++) {
        $pair = New-Object 'double[]' 2
        $pair[0] = [double]$esriPairs[$i][1]
        $pair[1] = [double]$esriPairs[$i][0]
        $out[$i] = $pair
    }
    return $out
}

function GreatCircleMeters([double]$lat1, [double]$lng1, [double]$lat2, [double]$lng2) {
    $R = 6371000.0
    $p1 = $lat1 * [math]::PI / 180.0; $p2 = $lat2 * [math]::PI / 180.0
    $dp = ($lat2 - $lat1) * [math]::PI / 180.0; $dl = ($lng2 - $lng1) * [math]::PI / 180.0
    $a = [math]::Sin($dp / 2) * [math]::Sin($dp / 2) + [math]::Cos($p1) * [math]::Cos($p2) * [math]::Sin($dl / 2) * [math]::Sin($dl / 2)
    $c = 2 * [math]::Atan2([math]::Sqrt($a), [math]::Sqrt(1 - $a))
    return $R * $c
}

function ParseLiteral($path) {
    $lines = Get-Content $path
    $pts = New-Object System.Collections.ArrayList
    foreach ($l in $lines) {
        if ($l -match '(-?[\d\.]+)\s+to\s+(-?[\d\.]+)') {
            $pair = New-Object 'double[]' 2
            $pair[0] = [double]$matches[1]
            $pair[1] = [double]$matches[2]
            [void]$pts.Add($pair)
        }
    }
    return $pts
}

function VerifyOrder($rawLatLng, $simpLatLng) {
    $lastIdx = -1
    $ok = $true
    $maxGap = 0.0
    $maxGapDesc = ""
    for ($k = 0; $k -lt $simpLatLng.Count; $k++) {
        $sp = $simpLatLng[$k]
        $bestIdx = -1; $bestDist = [double]::MaxValue
        for ($i = 0; $i -lt $rawLatLng.Count; $i++) {
            $dlat = $rawLatLng[$i][0] - $sp[0]; $dlng = $rawLatLng[$i][1] - $sp[1]
            $d = $dlat * $dlat + $dlng * $dlng
            if ($d -lt $bestDist) { $bestDist = $d; $bestIdx = $i }
        }
        if ($bestDist -gt 1e-14) {
            Write-Output "  WARNING: output point $k ($($sp[0]),$($sp[1])) has no exact raw match (nearest dist2=$bestDist) -- is this really derived from this raw path?"
        }
        if ($bestIdx -le $lastIdx) {
            $ok = $false
            Write-Output "  ORDER VIOLATION at output index $k -> raw index $bestIdx (previous raw index $lastIdx)"
        }
        if ($k -gt 0) {
            $prev = $simpLatLng[$k - 1]
            $gap = GreatCircleMeters $prev[0] $prev[1] $sp[0] $sp[1]
            if ($gap -gt $maxGap) {
                $maxGap = $gap
                $maxGapDesc = "point $($k-1) ($($prev[0]),$($prev[1])) -> point $k ($($sp[0]),$($sp[1]))"
            }
        }
        $lastIdx = $bestIdx
    }
    Write-Output "Strictly ascending raw-index order: $ok"
    Write-Output "Largest consecutive-point gap: $([math]::Round($maxGap, 1)) m  [$maxGapDesc]"
    if (-not $ok) {
        Write-Output "FAIL -- do not use this output; re-run dp_simplify.ps1 and check for a reordering bug (see this script's header comment)."
        exit 1
    }
}

$feature = FetchRiver
$paths = $feature.geometry.paths
$chosenIndex = $PathIndex
if ($chosenIndex -lt 0) {
    $chosenIndex = 0
    $best = $paths[0].Count
    for ($i = 1; $i -lt $paths.Count; $i++) {
        if ($paths[$i].Count -gt $best) { $best = $paths[$i].Count; $chosenIndex = $i }
    }
}
$rawLatLng = ToLatLng $paths[$chosenIndex]
$simpLatLng = ParseLiteral $LiteralFile

Write-Output "--- $($feature.attributes.Name), path index $chosenIndex ($($rawLatLng.Count) raw vertices), $($simpLatLng.Count) simplified points from $LiteralFile ---"
VerifyOrder $rawLatLng $simpLatLng
