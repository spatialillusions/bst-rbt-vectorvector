# Dot-source to select the same Temurin build as bst-rbt-planetiler: . .\env.ps1
$sharedToolRoot = Join-Path $PSScriptRoot '..\bst-rbt-planetiler\.tools'
if ($env:JDK25_HOME) { $env:JAVA_HOME = $env:JDK25_HOME }
else {
    foreach ($candidate in @((Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium\jdk-25.0.4.1+1'))) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) {
            $env:JAVA_HOME = (Resolve-Path -LiteralPath $candidate).Path
            break
        }
    }
}
$sharedMavenBin = Join-Path $sharedToolRoot 'maven\apache-maven-3.9.11\bin'
if (Test-Path -LiteralPath $sharedMavenBin) { $env:PATH = "$sharedMavenBin;$env:PATH" }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
