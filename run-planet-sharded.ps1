[CmdletBinding()]
param(
    [string]$ElevationArchive = 'D:\Mapterhorn\mapterhorn.pmtiles',
    [string[]]$AdditionalPmtiles = @(),
    [string]$OsmPbf = 'D:\OpenStreetMap\planet-260921.osm.pbf',
    [string]$GlacierWkb,
    [string]$SpatialData,
    [string]$OutputDir = (Join-Path $PSScriptRoot 'out\planet'),
    [ValidateRange(1, 1024)][int]$Parallelism = 8,
    [ValidateRange(1, 64)][int]$ShardsX = 64,
    [ValidateRange(1, 64)][int]$ShardsY = 64,
    [ValidateRange(9, 13)][int]$MaxZoom = 13,
    [string]$Bbox,
    [string]$TileList,
    [string]$JavaHeap = '16g',
    [ValidateRange(1, 32768)][int]$ElevationCacheTiles = 512,
    [ValidateRange(1, 32768)][int]$GlacierCacheTiles = 1024,
    [ValidateRange(0, 65536)][int]$ElevationCacheMiB = 384,
    [ValidateRange(0, 65536)][int]$GlacierCacheMiB = 128,
    [ValidateRange(0, 256)][int]$TileBlockSize = 8,
    [switch]$ReopenArchivesAfterShard,
    [ValidateSet('g1', 'parallel')][string]$GarbageCollector = 'g1',
    [ValidateSet('auto', '4m', '8m', '16m')][string]$G1HeapRegionSize = 'auto',
    [switch]$NoOsm,
    [switch]$Build,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
if ($GarbageCollector -ne 'g1' -and $G1HeapRegionSize -ne 'auto') {
    throw '-G1HeapRegionSize requires -GarbageCollector g1'
}
if ($NoOsm -and ($GlacierWkb -or $SpatialData)) { throw '-NoOsm cannot be combined with glacier/spatial inputs' }

$jar = Join-Path $PSScriptRoot 'target\vectorvector-cli.jar'
$cliArgs = @(
    '--profile', 'rbt', '--dataFormat', 'mapterhorn',
    '--pmtiles', $ElevationArchive,
    '-minZ', '6', '-maxZ', "$MaxZoom",
    '-minX', '0', '-maxX', '63', '-minY', '0', '-maxY', '63',
    '--parallelism', "$Parallelism", '--shardsX', "$ShardsX", '--shardsY', "$ShardsY",
    '--resumableShards=true', '--elevationCacheTiles', "$ElevationCacheTiles", '--glacierCacheTiles', "$GlacierCacheTiles",
    '--elevationCacheMiB', "$ElevationCacheMiB", '--glacierCacheMiB', "$GlacierCacheMiB", '--tileBlockSize', "$TileBlockSize",
    '-f', 'mbtiles', '-o', $OutputDir
)
foreach ($archive in $AdditionalPmtiles) { $cliArgs += @('--pmtiles', $archive) }
if ($Bbox) { $cliArgs += @('--bbox', $Bbox) }
if ($TileList) { $cliArgs += @('--tileList', $TileList) }
if (-not $NoOsm) { $cliArgs += @('--glaciers', $OsmPbf, '--peaks') }
if ($GlacierWkb) { $cliArgs += @('--glacierWkb', $GlacierWkb) }
if ($SpatialData) { $cliArgs += @('--spatialData', $SpatialData) }
if ($ReopenArchivesAfterShard) { $cliArgs += '--reopenArchivesAfterShard' }
$javaArgs = @('-Xms1g', "-Xmx$JavaHeap", '-XX:+ExitOnOutOfMemoryError', '-jar', $jar) + $cliArgs
[string[]]$gcArgs = if ($GarbageCollector -eq 'g1') { '-XX:+UseG1GC' } else { '-XX:+UseParallelGC' }
if ($G1HeapRegionSize -ne 'auto') { $gcArgs += "-XX:G1HeapRegionSize=$G1HeapRegionSize" }
$javaArgs = @($gcArgs) + $javaArgs
Write-Host ('java ' + (($javaArgs | ForEach-Object { '"' + $_ + '"' }) -join ' '))
if ($DryRun) { return }

foreach ($inputPath in (@($ElevationArchive) + $AdditionalPmtiles)) {
    if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw "Missing elevation archive: $inputPath" }
}
if ($TileList -and -not (Test-Path -LiteralPath $TileList -PathType Leaf)) { throw "Missing tile list: $TileList" }
if (-not $NoOsm -and -not (Test-Path -LiteralPath $OsmPbf -PathType Leaf)) { throw "Missing OSM PBF: $OsmPbf" }
if ($GlacierWkb -and -not (Test-Path -LiteralPath $GlacierWkb -PathType Leaf)) { throw "Missing glacier WKB: $GlacierWkb" }
if ($SpatialData -and -not (Test-Path -LiteralPath (Join-Path $SpatialData 'manifest.properties') -PathType Leaf)) { throw "Missing prepared spatial manifest: $SpatialData" }

Push-Location $PSScriptRoot
try {
    # Build once. Reusing the same executable is required by the resume manifest.
    if ($Build -or -not (Test-Path -LiteralPath $jar)) {
        & (Join-Path $PSScriptRoot 'build-jdk21.ps1') -MavenArgs @('-B', 'package')
        if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
    }
    $logDir = Join-Path $OutputDir 'logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $runLog = Join-Path $logDir (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    Write-Host "Run log: $runLog.log"
    # Explicit argument arrays preserve Windows paths containing spaces.
    try {
        # Windows PowerShell turns native stderr into ErrorRecords, including harmless JVM notices.
        $ErrorActionPreference = 'Continue'
        & java @javaArgs 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath "$runLog.log"
        $runExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = 'Stop' }
    if ($runExitCode -ne 0) { throw "Planet build failed with exit code $runExitCode; see $runLog.log" }
}
finally { Pop-Location }
