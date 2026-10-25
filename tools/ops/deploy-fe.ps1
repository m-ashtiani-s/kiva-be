<#
.SYNOPSIS
    Builds the shop (kiva-fe) or the panel (kiva-backoffice-fe) on this machine and puts it on the server, with the
    previous release kept and brought back by itself if the new one doesn't answer (LAUNCH.md, steps 6.1 and 6.4).

.DESCRIPTION
    1. npm ci and npm run build in the frontend's folder, with the NEXT_PUBLIC_* of tools/ops/fe/<app>.build.env
       (they are read at build time, so a wrong one needs a new build, not a restart).
    2. Packs the build: Next's standalone output when the project has `output: 'standalone'`, otherwise .next,
       public and package files (the server then runs npm ci --omit=dev itself).
    3. scp to the server and tools/ops/deploy-fe-remote.sh there, as root: unpack beside the running release, swap,
       restart under pm2 and wait for an answer on 127.0.0.1; no answer in 60 s puts the previous release back.

.EXAMPLE
    .\tools\ops\deploy-fe.ps1 -App panel
.EXAMPLE
    .\tools\ops\deploy-fe.ps1 -App shop -Source C:\work\kiva-fe -Server root@203.0.113.10
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('shop', 'panel')][string]$App,
    # The frontend's folder; by default next to this repository.
    [string]$Source,
    # Anything ssh accepts: a Host of ~/.ssh/config (LAUNCH.md sets up "kiva") or root@address.
    [string]$Server = $(if ($env:KIVA_SSH_TARGET) { $env:KIVA_SSH_TARGET } else { 'kiva' }),
    # Skip npm ci when node_modules is already what package-lock.json says.
    [switch]$NoInstall,
    # Where the frontend lives on the server, when not the defaults of deploy-fe-remote.sh
    # (shop: /opt/kiva-fe, kivafe, 3100, kiva-fe; panel: /opt/kiva-bo, kivabo, 3200, kiva-bo).
    [string]$RemoteRoot,
    [string]$RemoteUser,
    [int]$Port,
    [string]$Pm2Name
)

$ErrorActionPreference = 'Stop'
$opsDir = $PSScriptRoot
$repoName = @{ shop = 'kiva-fe'; panel = 'kiva-backoffice-fe' }[$App]
if (-not $Source) { $Source = Join-Path (Split-Path (Split-Path (Split-Path $opsDir -Parent) -Parent) -Parent) $repoName }
if (-not (Test-Path (Join-Path $Source 'package.json'))) { throw "No package.json in $Source; pass -Source with the $repoName folder." }

# Judged by the exit code only: Windows PowerShell turns a native tool's stderr (Gradle's progress, Java's notices)
# into errors when the output is redirected, and 'Stop' would end the script on the first one.
function Invoke-Native([string]$what, [scriptblock]$command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

# A Linux script copied from a Windows checkout may carry CRLF; bash would choke on it.
function Copy-WithLf([string]$from, [string]$to) {
    $text = [IO.File]::ReadAllText($from) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($to, $text, (New-Object Text.UTF8Encoding $false))
}

# --- 1. build ---
$buildEnv = Join-Path $opsDir "fe\$App.build.env"
$saved = @{}
foreach ($line in Get-Content $buildEnv -Encoding UTF8) {
    if ($line -match '^\s*(#|$)') { continue }
    $name, $value = $line -split '=', 2
    $name = $name.Trim()
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $value.Trim())
    Write-Host "build env $name=$($value.Trim())"
}

Push-Location $Source
try {
    # Not a git checkout is fine (the release then says commit=unknown); 'Stop' would end the script on git's stderr.
    $ErrorActionPreference = 'Continue'
    $commit = git rev-parse --short HEAD 2>$null
    $dirty = git status --porcelain 2>$null
    $ErrorActionPreference = 'Stop'
    if (-not $commit) { $commit = 'unknown' }
    if ($dirty) { Write-Warning "$repoName has uncommitted changes; they go to the server too." }
    if (-not $NoInstall) { Invoke-Native 'npm ci' { npm ci --no-audit --no-fund } }
    Invoke-Native 'npm run build' { npm run build }
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
}

# --- 2. pack ---
$stage = Join-Path ([IO.Path]::GetTempPath()) "kiva-$App-release"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null

$standalone = Join-Path $Source '.next\standalone'
if (Test-Path (Join-Path $standalone 'server.js')) {
    $mode = 'standalone'
    Copy-Item -Recurse (Join-Path $standalone '*') $stage
    New-Item -ItemType Directory -Force (Join-Path $stage '.next') | Out-Null
    Copy-Item -Recurse (Join-Path $Source '.next\static') (Join-Path $stage '.next\static')
    if (Test-Path (Join-Path $Source 'public')) { Copy-Item -Recurse (Join-Path $Source 'public') (Join-Path $stage 'public') }
} else {
    $mode = 'npm'
    New-Item -ItemType Directory -Force (Join-Path $stage '.next') | Out-Null
    Get-ChildItem (Join-Path $Source '.next') | Where-Object { $_.Name -ne 'cache' } | Copy-Item -Recurse -Destination (Join-Path $stage '.next')
    foreach ($item in @('public', 'package.json', 'package-lock.json', 'next.config.js', 'next.config.mjs', 'next.config.ts')) {
        $path = Join-Path $Source $item
        if (Test-Path $path) { Copy-Item -Recurse $path (Join-Path $stage $item) }
    }
}
$marker = "app=$App`nmode=$mode`ncommit=$commit`nbuiltOn=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))`n"
[IO.File]::WriteAllText((Join-Path $stage 'kiva-release.txt'), $marker, (New-Object Text.UTF8Encoding $false))

$tarball = Join-Path ([IO.Path]::GetTempPath()) "kiva-$App-release.tgz"
if (Test-Path $tarball) { Remove-Item -Force $tarball }
Invoke-Native 'tar' { tar -czf $tarball -C $stage . }
Write-Host "packed $repoName $commit ($mode) into $tarball, $([math]::Round((Get-Item $tarball).Length / 1MB, 1)) MB"

# --- 3. ship ---
$remoteScript = Join-Path ([IO.Path]::GetTempPath()) 'deploy-fe-remote.sh'
Copy-WithLf (Join-Path $opsDir 'deploy-fe-remote.sh') $remoteScript
Invoke-Native 'scp' { scp $tarball $remoteScript "${Server}:/tmp/" }
$overrides = @()
if ($RemoteRoot) { $overrides += "KIVA_FE_ROOT='$RemoteRoot'" }
if ($RemoteUser) { $overrides += "KIVA_FE_USER='$RemoteUser'" }
if ($Port) { $overrides += "KIVA_FE_PORT=$Port" }
if ($Pm2Name) { $overrides += "KIVA_FE_PM2_NAME='$Pm2Name'" }
$remote = (@('env') + $overrides + @('bash', '/tmp/deploy-fe-remote.sh', $App, "/tmp/kiva-$App-release.tgz")) -join ' '
Invoke-Native 'deploy on the server' { ssh $Server $remote }
Write-Host "$repoName $commit is live."
