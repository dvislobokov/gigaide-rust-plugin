# Builds the plugin ZIP with the JBR of the target IDE (no system JDK or Gradle needed).
#   .\build.ps1            tests + buildPlugin  -> build\distributions\gigaide-rust-<version>.zip
#   .\build.ps1 -NoTests   buildPlugin only
#   .\build.ps1 -Run       build, then start the sandbox IDE on .\playground
#   .\build.ps1 -IdePath "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2026.1.4"   another IDE (gradle.properties localIdePath is the default)
param([switch]$NoTests, [switch]$Run, [string]$IdePath)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $IdePath) {
    $line = Get-Content gradle.properties | Where-Object { $_ -match '^\s*localIdePath\s*=' } | Select-Object -First 1
    if ($null -eq $line) { throw "gradle.properties: no localIdePath; pass -IdePath" }
    $IdePath = ($line -split '=', 2)[1].Trim()
}
$jbr = Join-Path $IdePath "jbr"
if (-not (Test-Path (Join-Path $jbr "bin\java.exe"))) { throw "no JBR at $jbr (is $IdePath an installed JetBrains IDE?)" }
$env:JAVA_HOME = $jbr
Write-Host "JAVA_HOME = $jbr"

# --offline: online, Gradle tries to fetch java-compiler-ant-tasks for test instrumentation, which this machine's proxy blocks
$tasks = @(); if (-not $NoTests) { $tasks += "test" }; $tasks += "buildPlugin"
& .\gradlew.bat @tasks -q --offline "-PlocalIdePath=$IdePath"
if (-not $?) { throw "gradle failed" }

$version = ((Get-Content gradle.properties | Where-Object { $_ -match '^\s*pluginVersion\s*=' }) -split '=', 2)[1].Trim()
$zip = Join-Path $PSScriptRoot "build\distributions\gigaide-rust-$version.zip"
if (-not (Test-Path $zip)) { throw "no ZIP at $zip" }
Write-Host "Plugin: $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"
Write-Host "Install: Settings | Plugins | gear | Install Plugin from Disk..."

if ($Run) { & .\gradlew.bat runIde --offline --no-configuration-cache -q "-PlocalIdePath=$IdePath" "--args=$PSScriptRoot\playground" }
