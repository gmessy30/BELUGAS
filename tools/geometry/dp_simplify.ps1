<#
.SYNOPSIS
  Fetches a river's raw geometry from the KPB 21.18 Anadromous Streams FeatureServer and
  simplifies it with Douglas-Peucker, in the same flat-meters projection CoastlineGeometry.kt's
  nearestSegment already uses.

.EXAMPLE
  .\dp_simplify.ps1 -RiverName "Kenai River" -PathIndex 2 -ToleranceMeters 50
  .\dp_simplify.ps1 -AwcNo "244-30-10050" -ToleranceMeters 50

  See ../README.md for how to find a river's AWC ID / path index.
#>
param(
    [string]$RiverName,
    [string]$AwcNo,
    # Index into the feature's `paths` array, for rivers with multiple disconnected mapped
    # reaches (see README). -1 = use the longest path by raw vertex count.
    [int]$PathIndex = -1,
    [double]$ToleranceMeters = 50.0,
    [string]$OutFile
)

$FeatureServerUrl = "https://services.arcgis.com/ba4DH9pIcqkXJVfl/arcgis/rest/services/KPB_2118_view/FeatureServer/0/query"
$MetersPerDegLat = 111320.0

function MetersPerDegLng([double]$lat) { return $MetersPerDegLat * [math]::Cos($lat * [math]::PI / 180.0) }

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
    if ($resp.features.Count -gt 1) {
        Write-Warning "Multiple features matched ($where) -- using the first (OBJECTID $($resp.features[0].attributes.OBJECTID))."
    }
    return $resp.features[0]
}

# EsriPairs: array of [lng,lat]. Returns [double[]][] of [lat,lng].
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

function ProjectPoints($latlng, [double]$refLat) {
    $mPerLng = MetersPerDegLng $refLat
    $n = $latlng.Count
    $out = New-Object 'object[]' $n
    for ($i = 0; $i -lt $n; $i++) {
        $xy = New-Object 'double[]' 2
        $xy[0] = $latlng[$i][1] * $mPerLng
        $xy[1] = $latlng[$i][0] * $MetersPerDegLat
        $out[$i] = $xy
    }
    return $out
}

function PerpDist([double[]]$pt, [double[]]$a, [double[]]$b) {
    $dx = $b[0] - $a[0]; $dy = $b[1] - $a[1]
    $lenSq = $dx * $dx + $dy * $dy
    if ($lenSq -eq 0) {
        $ddx = $pt[0] - $a[0]; $ddy = $pt[1] - $a[1]
        return [math]::Sqrt($ddx * $ddx + $ddy * $ddy)
    }
    $t = (($pt[0] - $a[0]) * $dx + ($pt[1] - $a[1]) * $dy) / $lenSq
    if ($t -lt 0.0) { $t = 0.0 }
    if ($t -gt 1.0) { $t = 1.0 }
    $projx = $a[0] + $t * $dx; $projy = $a[1] + $t * $dy
    $ddx = $pt[0] - $projx; $ddy = $pt[1] - $projy
    return [math]::Sqrt($ddx * $ddx + $ddy * $ddy)
}

# Recursive rather than an explicit stack -- PowerShell's `New-Object Stack[Tuple[int,int]]`
# doesn't parse the nested generic type literal cleanly. Max depth here is bounded by raw
# vertex count (hundreds, not thousands), well within default recursion limits.
function DpRecurse($proj, [bool[]]$keep, [int]$i0, [int]$i1, [double]$eps) {
    if ($i1 - $i0 -lt 2) { return }
    $maxD = -1.0; $maxIdx = -1
    for ($i = $i0 + 1; $i -lt $i1; $i++) {
        $d = PerpDist $proj[$i] $proj[$i0] $proj[$i1]
        if ($d -gt $maxD) { $maxD = $d; $maxIdx = $i }
    }
    if ($maxD -gt $eps) {
        $keep[$maxIdx] = $true
        DpRecurse $proj $keep $i0 $maxIdx $eps
        DpRecurse $proj $keep $maxIdx $i1 $eps
    }
}

function DouglasPeucker($proj, [double]$eps) {
    $n = $proj.Count
    $keep = New-Object bool[] $n
    $keep[0] = $true; $keep[$n - 1] = $true
    DpRecurse $proj $keep 0 ($n - 1) $eps
    return $keep
}

function NearestSegDist([double[]]$pt, $simpProj) {
    $best = [double]::MaxValue
    for ($i = 0; $i -lt $simpProj.Count - 1; $i++) {
        $d = PerpDist $pt $simpProj[$i] $simpProj[$i + 1]
        if ($d -lt $best) { $best = $d }
    }
    return $best
}

# Emits kept points by scanning raw index ascending (not recursion visit order) -- this is
# what guarantees the output is a strictly-ordered walk along the river, not just a set of
# retained vertices. See ../README.md and verify_order.ps1.
function Simplify($rawLatLng, [double]$eps) {
    $sum = 0.0
    foreach ($p in $rawLatLng) { $sum += $p[0] }
    $refLat = $sum / $rawLatLng.Count

    $proj = ProjectPoints $rawLatLng $refLat
    $keep = DouglasPeucker $proj $eps

    $simpLatLng = New-Object System.Collections.ArrayList
    $simpProj = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $rawLatLng.Count; $i++) {
        if ($keep[$i]) {
            [void]$simpLatLng.Add($rawLatLng[$i])
            [void]$simpProj.Add($proj[$i])
        }
    }

    $maxDev = 0.0
    foreach ($p in $proj) {
        $d = NearestSegDist $p $simpProj
        if ($d -gt $maxDev) { $maxDev = $d }
    }

    return @{ Points = $simpLatLng; Count = $simpLatLng.Count; MaxDeviationMeters = $maxDev; RefLat = $refLat }
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
    if ($paths.Count -gt 1) {
        Write-Output "Feature has $($paths.Count) disconnected path(s); using longest (index $chosenIndex, $best raw vertices)."
    }
}
$rawLatLng = ToLatLng $paths[$chosenIndex]

$result = Simplify $rawLatLng $ToleranceMeters

Write-Output "=== $($feature.attributes.Name) (AWC $($feature.attributes.AWC_No)), path index $chosenIndex, raw $($rawLatLng.Count) pts, tolerance ${ToleranceMeters}m ==="
Write-Output "Simplified point count: $($result.Count)"
Write-Output "Actual max deviation (m): $([math]::Round($result.MaxDeviationMeters, 3))"

$lines = New-Object System.Collections.ArrayList
foreach ($p in $result.Points) { [void]$lines.Add(("    {0:F7} to {1:F7}" -f $p[0], $p[1])) }
$literalText = $lines -join ",`n"

if ($OutFile) {
    Set-Content -Path $OutFile -Value $literalText -Encoding utf8
    Write-Output "Wrote simplified points (Kotlin `lat to lng` literal format) to $OutFile"
} else {
    Write-Output "--- Kotlin literal (lat to lng) ---"
    Write-Output $literalText
}
