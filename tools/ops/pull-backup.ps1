<#
.SYNOPSIS
    Copies the server's latest nightly backup to this PC and checks it (LAUNCH.md, step 6.3: the copy outside the
    server, when the backup isn't sent to a cloud bucket).

.DESCRIPTION
    Finds the newest day under /root/backups, copies its kiva.dump, media.tgz and SHA256SUMS into
    <Destination>\<day>, checks every file's SHA-256, and keeps the newest -Keep days here.
    A day already here and whole is not copied again.

.EXAMPLE
    .\tools\ops\pull-backup.ps1 -Destination D:\kiva-backups
#>
param(
    [string]$Destination = (Join-Path $HOME 'kiva-backups'),
    [string]$Server = $(if ($env:KIVA_SSH_TARGET) { $env:KIVA_SSH_TARGET } else { 'kiva' }),
    [int]$Keep = 8
)

# Native tools (ssh, scp) are judged by their exit code, not 'Stop': Windows PowerShell turns their stderr into errors
# when the output is redirected.
$ErrorActionPreference = 'Continue'
$latest = (ssh $Server 'ls -1d /root/backups/????-??-?? | tail -n 1' 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $latest) { throw "No backup found on $Server under /root/backups." }
$latest = $latest.Trim()
$day = Split-Path $latest -Leaf
$target = Join-Path $Destination $day

function Test-Backup([string]$folder) {
    $sums = Join-Path $folder 'SHA256SUMS'
    if (-not (Test-Path $sums)) { return $false }
    foreach ($line in Get-Content $sums) {
        $expected, $name = $line -split '\s+\*?', 2
        $file = Join-Path $folder $name.Trim()
        if (-not (Test-Path $file)) { return $false }
        if ((Get-FileHash -Algorithm SHA256 $file).Hash -ne $expected.ToUpper()) { return $false }
    }
    return $true
}

if ((Test-Path $target) -and (Test-Backup $target)) {
    Write-Host "$day is already here and whole: $target"
} else {
    New-Item -ItemType Directory -Force $target | Out-Null
    foreach ($name in @('SHA256SUMS', 'kiva.dump', 'media.tgz')) {
        scp "${Server}:$latest/$name" (Join-Path $target $name) 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) { throw "scp of $name failed" }
    }
    if (-not (Test-Backup $target)) { throw "The copy of $day doesn't match its SHA256SUMS; it is not a backup. Run this again." }
    Write-Host "$day copied and checked: $target"
}

Get-ChildItem $Destination -Directory | Where-Object { $_.Name -match '^\d{4}-\d{2}-\d{2}$' } |
    Sort-Object Name -Descending | Select-Object -Skip $Keep | ForEach-Object {
        Remove-Item -Recurse -Force $_.FullName
        Write-Host "removed $($_.Name), more than $Keep days here"
    }
