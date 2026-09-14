# Standard way the user applies a migration by hand (see CLAUDE.md's "Migrations" section).
# Finds the newest file in supabase\migrations\, confirms with the user, then runs it via the
# Supabase CLI against the linked project.

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$migrationsDir = Join-Path $repoRoot "supabase\migrations"

$latest = Get-ChildItem -Path $migrationsDir -Filter "*.sql" |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $latest) {
    Write-Error "No .sql files found in $migrationsDir"
    exit 1
}

Write-Host "Latest migration: $($latest.Name)"
$confirm = Read-Host "Apply this migration to the linked project? (Y/N)"

if ($confirm -ne "Y" -and $confirm -ne "y") {
    Write-Host "Aborted."
    exit 0
}

$supabase = "$env:LOCALAPPDATA\supabase-cli\supabase.exe"
& $supabase db query --linked --file $latest.FullName
