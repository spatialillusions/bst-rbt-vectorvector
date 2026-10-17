# Dot-source to use the project-local toolchain:  . .\env.ps1
$localToolRoot = Join-Path $PSScriptRoot '.tools'
if (Test-Path -LiteralPath $localToolRoot -PathType Container) {
    $localJdk21 = Get-ChildItem $localToolRoot -Directory -Filter 'jdk-21*' | Select-Object -First 1
    if ($localJdk21) { $env:JAVA_HOME = $localJdk21.FullName }
    $localMavenDirectory = Get-ChildItem $localToolRoot -Directory -Filter 'apache-maven-*' | Select-Object -First 1
    if ($localMavenDirectory) { $env:PATH = "$($localMavenDirectory.FullName)\bin;$env:PATH" }
    $env:PATH = "$localToolRoot\bin;$env:PATH"
}
elseif ($env:JDK21_HOME) { $env:JAVA_HOME = $env:JDK21_HOME }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
