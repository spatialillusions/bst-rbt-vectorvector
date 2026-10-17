# Dot-source to use the project-local toolchain:  . .\env.ps1
$root = $PSScriptRoot
$env:JAVA_HOME = (Get-ChildItem "$root\.tools" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$mvnHome = (Get-ChildItem "$root\.tools" -Directory -Filter 'apache-maven-*' | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$mvnHome\bin;$root\.tools\bin;$env:PATH"
