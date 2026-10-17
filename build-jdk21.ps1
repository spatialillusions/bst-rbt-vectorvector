[CmdletBinding()]
param(
    [string]$JavaHome = $env:JDK21_HOME,
    [string]$MavenExecutable = 'mvn',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs = @('verify')
)

$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.tools'))) {
    $localJdk = Get-ChildItem (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-21*' | Select-Object -First 1
    if ($localJdk) { $JavaHome = $localJdk.FullName }
}
if (-not $JavaHome) { throw 'Set JDK21_HOME or pass -JavaHome with a JDK 21 directory.' }
$javaExe = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) { throw "Missing Java executable: $javaExe" }
$ErrorActionPreference = 'Continue'
$versionLines = & $javaExe -version 2>&1
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$version = ($versionLines | ForEach-Object { $_.ToString() }) -join "`n"
if ($javaExitCode -ne 0 -or $version -notmatch 'version "21(?:\.|"|-)') {
    throw "This project requires JDK 21. Selected Java reports: $version"
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
if ($MavenExecutable -eq 'mvn' -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.tools'))) {
    $localMaven = Get-ChildItem (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'apache-maven-*' | Select-Object -First 1
    if ($localMaven) { $MavenExecutable = Join-Path $localMaven.FullName 'bin\mvn.cmd' }
}
if (-not $MavenArgs -or $MavenArgs.Count -eq 0) { $MavenArgs = @('verify') }
Write-Host "Using JDK 21: $env:JAVA_HOME"
Push-Location $PSScriptRoot
try {
    # Windows PowerShell can turn harmless native stderr warnings into terminating
    # ErrorRecords when redirected. Use Maven's exit code to determine success.
    $ErrorActionPreference = 'Continue'
    & $MavenExecutable @MavenArgs 2>&1 | ForEach-Object { $_.ToString() }
    $buildExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
}
exit $buildExitCode
