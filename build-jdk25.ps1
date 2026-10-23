[CmdletBinding()]
param(
    [string]$JavaHome = $env:JDK25_HOME,
    [string]$MavenExecutable = 'mvn',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs = @('verify')
)

$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $sharedJdk = Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium\jdk-25.0.4.1+1'
    if (Test-Path -LiteralPath $sharedJdk) { $JavaHome = $sharedJdk }
}
if (-not $JavaHome -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.tools'))) {
    $localJdk = Get-ChildItem (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-25*' | Select-Object -First 1
    if ($localJdk) { $JavaHome = $localJdk.FullName }
}
if (-not $JavaHome) { throw 'Set JDK25_HOME or pass -JavaHome with a JDK 25 directory.' }
$javaExe = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) { throw "Missing Java executable: $javaExe" }
$ErrorActionPreference = 'Continue'
$versionLines = & $javaExe -version 2>&1
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$version = ($versionLines | ForEach-Object { $_.ToString() }) -join "`n"
if ($javaExitCode -ne 0 -or $version -notmatch 'Temurin-25\.0\.4\.1\+1(?:\D|$)') {
    throw "This project requires Temurin 25.0.4.1+1, matching bst-rbt-planetiler. Selected Java reports: $version"
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
if ($MavenExecutable -eq 'mvn' -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.tools'))) {
    $localMaven = Get-ChildItem (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'apache-maven-*' | Select-Object -First 1
    if ($localMaven) { $MavenExecutable = Join-Path $localMaven.FullName 'bin\mvn.cmd' }
}
if ($MavenExecutable -eq 'mvn') {
    $sharedMaven = Join-Path $PSScriptRoot '..\bst-rbt-planetiler\.tools\maven\apache-maven-3.9.11\bin\mvn.cmd'
    if (Test-Path -LiteralPath $sharedMaven) { $MavenExecutable = $sharedMaven }
}
if (-not $MavenArgs -or $MavenArgs.Count -eq 0) { $MavenArgs = @('verify') }
Write-Host "Using JDK 25: $env:JAVA_HOME"
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
