<#
.SYNOPSIS
    Builds kiva-be on this machine and puts it on the server in one command, with the previous release brought back by
    itself when the new one doesn't come up (LAUNCH.md, step 6.4).

.DESCRIPTION
    1. gradlew installDist (no tests: run them before, on the commit you deploy).
    2. A copy of build\install\kiva-be with kiva-release.txt (commit, time), packed with tar.
    3. scp to the server and tools/ops/deploy-remote.sh there, as root: a quick pg_dump, the swap of /opt/kiva, and a
       90-second wait for /health; then either UP, or the previous release running again and this script failing.

    -BreakOnPurpose ships a release whose bin/kiva-be runs `java` with no arguments, to watch the rollback work once
    (step 6.4 is done when that deploy fails and the site stays up).

.EXAMPLE
    .\tools\ops\deploy.ps1
.EXAMPLE
    .\tools\ops\deploy.ps1 -BreakOnPurpose
#>
param(
    # Anything ssh accepts: a Host of ~/.ssh/config (LAUNCH.md sets up "kiva") or root@address.
    [string]$Server = $(if ($env:KIVA_SSH_TARGET) { $env:KIVA_SSH_TARGET } else { 'kiva' }),
    [switch]$BreakOnPurpose
)

$ErrorActionPreference = 'Stop'
$opsDir = $PSScriptRoot
$repo = Split-Path (Split-Path $opsDir -Parent) -Parent

# Judged by the exit code only: Windows PowerShell turns a native tool's stderr (Gradle's progress, Java's notices)
# into errors when the output is redirected, and 'Stop' would end the script on the first one.
function Invoke-Native([string]$what, [scriptblock]$command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

function Write-Lf([string]$path, [string]$text) {
    [IO.File]::WriteAllText($path, ($text -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
}

# A JDK 21 for Gradle: JAVA_HOME, else java on the PATH, else one IntelliJ downloaded.
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $jdk = Get-ChildItem (Join-Path $HOME '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '21' } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'No JDK 21: set JAVA_HOME.' }
    $env:JAVA_HOME = $jdk.FullName
    Write-Host "JAVA_HOME=$($jdk.FullName)"
}

Push-Location $repo
try {
    $commit = (git rev-parse --short HEAD).Trim()
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    if (git status --porcelain) { Write-Warning 'kiva-be has uncommitted changes; they go to the server too.' }
    if ($branch -ne 'main') { Write-Warning "Deploying branch $branch, not main." }
    Invoke-Native 'gradlew installDist' { .\gradlew.bat installDist --console=plain }
} finally {
    Pop-Location
}

$stage = Join-Path ([IO.Path]::GetTempPath()) 'kiva-be-release'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
Copy-Item -Recurse (Join-Path $repo 'build\install\kiva-be') $stage
$marker = "commit=$commit`nbranch=$branch`nbuiltOn=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))`n"
if ($BreakOnPurpose) {
    Write-Warning 'This release is broken on purpose: the server must put the previous one back.'
    Write-Lf (Join-Path $stage 'bin\kiva-be') "#!/bin/sh`n# deploy.ps1 -BreakOnPurpose: java with no arguments exits at once, so /health never comes up.`nexec java`n"
    $marker += "brokenOnPurpose=true`n"
}
Write-Lf (Join-Path $stage 'kiva-release.txt') $marker

$tarball = Join-Path ([IO.Path]::GetTempPath()) 'kiva-be-release.tgz'
if (Test-Path $tarball) { Remove-Item -Force $tarball }
Invoke-Native 'tar' { tar -czf $tarball -C $stage . }
Write-Host "packed kiva-be $commit ($branch), $([math]::Round((Get-Item $tarball).Length / 1MB, 1)) MB"

$remoteScript = Join-Path ([IO.Path]::GetTempPath()) 'deploy-remote.sh'
Write-Lf $remoteScript ([IO.File]::ReadAllText((Join-Path $opsDir 'deploy-remote.sh')))
Invoke-Native 'scp' { scp $tarball $remoteScript "${Server}:/tmp/" }
Invoke-Native 'deploy on the server' { ssh $Server 'bash /tmp/deploy-remote.sh /tmp/kiva-be-release.tgz' }
Write-Host "kiva-be $commit is live."
