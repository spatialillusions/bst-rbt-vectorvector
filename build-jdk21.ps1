# Compatibility entry point; builds now use the Planetiler JDK 25 toolchain.
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JDK25_HOME,
    [string]$MavenExecutable = 'mvn',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs = @('verify')
)
& (Join-Path $PSScriptRoot 'build-jdk25.ps1') -JavaHome $JavaHome -MavenExecutable $MavenExecutable -MavenArgs $MavenArgs
exit $LASTEXITCODE
