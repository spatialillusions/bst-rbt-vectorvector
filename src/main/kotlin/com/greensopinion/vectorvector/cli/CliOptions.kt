package com.greensopinion.vectorvector.cli

import com.greensopinion.vectorvector.LonLatBounds
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.TileRange
import com.greensopinion.vectorvector.profile.RbtZoomSettings
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File

@Command(name = "java --jar vectorvector-cli.jar")
class CliOptions {
    @Option(names = ["--parallelism"], description = ["Maximum concurrent tile workers. Defaults to 2."])
    var parallelism: Int = 2

    @Option(names = ["--shardsX"], description = ["Horizontal partitions at minZ, clamped to the range width."])
    var shardsX: Int = 1

    @Option(names = ["--shardsY"], description = ["Vertical partitions at minZ, clamped to the range height."])
    var shardsY: Int = 1

    @Option(names = ["--resumableShards"], arity = "0..1", fallbackValue = "true",
        description = ["Keep completed MBTiles shards and resume interrupted builds, then merge atomically."])
    var resumableShards: Boolean = false

    @Option(names = ["--shardWorkDir"], description = ["Shard artifacts directory; defaults to <output>/.shards."])
    var shardWorkDir: File? = null

    @Option(names = ["--elevationCacheTiles"], description = ["Maximum entries in each Mapterhorn elevation cache. Defaults to 128."])
    var elevationCacheTiles: Long = 128

    @Option(names = ["--glacierCacheTiles"], description = ["Maximum cached clipped glacier geometries. Defaults to 256."])
    var glacierCacheTiles: Long = 256

    @Option(
        names = ["-d", "--data"],
        description = ["The data directory containing elevation data in GeoTIFF format. Required for srtm and aw3d30."]
    )
    var dataDir: File? = null

    @Option(
        names = ["--pmtiles"],
        description = ["A Mapterhorn PMTiles archive, as a local path or http(s) URL. Repeatable, e.g. planet.pmtiles plus 6-x-y.pmtiles files. Required for --dataFormat mapterhorn."]
    )
    var pmtiles: MutableList<String> = mutableListOf()

    @Option(
        names = ["--pmtilesCacheDir"],
        description = ["Optional directory for caching remote PMTiles tile bytes on disk."]
    )
    var pmtilesCacheDir: File? = null

    @Option(
        names = ["--elevationExtent"],
        description = ["The elevation grid size per tile, 256 or 512. Defaults to 512 for mapterhorn and 256 otherwise."]
    )
    var elevationExtent: Int? = null

    @Option(
        names = ["--glaciers"],
        description = ["An OSM PBF file. Contour lines are split at natural=glacier outlines and tagged is_ice (rbt profile)."]
    )
    var glaciers: File? = null

    @Option(
        names = ["--glacierWkb"],
        description = ["Additional OSM-derived ice polygons in WGS84 Polygon/MultiPolygon WKB, e.g. the Antarctic ice sheet. Requires the rbt profile."]
    )
    var glacierWkb: File? = null

    @Option(
        names = ["--peaks"],
        arity = "0..1",
        fallbackValue = "",
        description = ["An OSM PBF file with natural=peak/volcano nodes, written as a 'peaks' layer. Without a value, the --glaciers file is used."]
    )
    var peaks: String? = null

    @Option(
        names = ["--peaksMinZoom"],
        description = ["The minimum zoom level of the peaks layer. Defaults to \${DEFAULT-VALUE}"]
    )
    var peaksMinZoom: Int = 9

    @Option(
        names = ["--profile"],
        description = ["The contour profile, one of \${COMPLETION-CANDIDATES}. Defaults to \${DEFAULT-VALUE}"]
    )
    var profile: ProfileName = ProfileName.default

    @Option(
        names = ["--vectorName"],
        description = ["The name of the vector tile output. Defaults to 'vector', or 'contours' for the rbt profile."]
    )
    var vectorName: String? = null

    @Option(
        names = ["--bbox"],
        description = ["Only generate tiles that intersect minLon,minLat,maxLon,maxLat. Without --area, the tile range is derived from it."]
    )
    var bbox: String? = null

    @Option(
        names = ["--rbtSmoothing"],
        description = ["Overrides RBT smoothing per zoom: 'z=sigmaPx/chaikinIterations/epsilon', comma separated, e.g. 12=1.5/2/3,13=1.5/3/3. A z of '*' applies to all zooms 9-13."]
    )
    var rbtSmoothing: String? = null

    @Option(
        names = ["--gzipTiles"],
        arity = "0..1",
        fallbackValue = "true",
        description = ["Gzip vector tiles (expected by QGIS and most MBTiles readers). Defaults to true for the rbt profile, false otherwise."]
    )
    var gzipTiles: Boolean? = null

    val resolvedGzipTiles: Boolean
        get() = gzipTiles ?: (profile == ProfileName.rbt)

    @Option(
        names = ["--tileList"],
        description = ["Only generate the tiles listed in this file, one z/x/y per line (within the area/zoom range)."]
    )
    var tileList: File? = null

    val resolvedTileList: Set<TileId>?
        get() = tileList?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }?.map { line ->
            val (z, x, y) = line.split("/").map { it.toInt() }
            TileId(z, x, y)
        }?.toSet()

    @Option(
        names = ["--dataFormat"],
        description = ["The format of data in the data directory"]
    )
    var dataFormat: DataSourceFormat = DataSourceFormat.aw3d30

    @Option(
        names = ["-o", "--output"],
        required = true,
        description = ["Specifies the directory where the generated output files will be saved. Defaults to \${DEFAULT-VALUE}"]
    )
    var outputDir: File = File(".")

    @Option(
        names = ["--terrarium"],
        description = ["Generates Terrarium-style raster tiles, commonly used for rendering 3D-like terrain features. Defaults to \${DEFAULT-VALUE}"]
    )
    var terrarium: Boolean = false

    @Option(
        names = ["--vector"],
        description = ["Generates vector tiles with contour lines. Defaults to \${DEFAULT-VALUE}"]
    )
    var vector: Boolean = true

    @Option(
        names = ["--hillshadeRaster"],
        arity = "0..1",
        fallbackValue = "true",
        description = ["Generates hillshade raster tiles, which simulate the shading effects of the sun on the terrain to enhance visual representation of elevation data. Defaults to true, except for the rbt profile or an elevation extent of 512."]
    )
    var hillshadeRaster: Boolean? = null

    @Option(
        names = ["-f", "--outputFormat"],
        required = true,
        description = ["The format of the output, must be one of \${COMPLETION-CANDIDATES}"]
    )
    var outputFormat: CliOutputFormat? = null

    @Option(
        names = ["-a", "--area"],
        description = ["The name of an area, which specifies a bounds for tile generation (minZ, maxZ, minX, maxX, minY, maxY). If specified, the area supersedes all Z/X/Y options. Must be one of \${COMPLETION-CANDIDATES}"]
    )
    var area: NamedArea? = null

    @Option(
        names = ["-minZ"],
        description = ["The minimum zoom level for which tiles are generated. Defaults to \${DEFAULT-VALUE}"]
    )
    var minZ: Int = NamedArea.world.bounds.minZ

    @Option(
        names = ["-maxZ"],
        description = ["The maximum zoom level for which tiles are generated. Defaults to \${DEFAULT-VALUE}"]
    )
    var maxZ: Int = NamedArea.world.bounds.maxZ

    @Option(
        names = ["-minX"],
        description = ["Specifies the minimum longitude (X coordinate) for the tile area at the minZ zoom level. Defaults to \${DEFAULT-VALUE}"]
    )
    var minX: Int = NamedArea.world.bounds.maxX

    @Option(
        names = ["-maxX"],
        description = ["Specifies the maximum longitude (X coordinate) for the tile area at the minZ zoom level. Defaults to \${DEFAULT-VALUE}"]
    )
    var maxX: Int = NamedArea.world.bounds.maxX

    @Option(
        names = ["-minY"],
        description = ["Specifies the minimum latitude (Y coordinate) for the tile area at the minZ zoom level. Defaults to \${DEFAULT-VALUE}"]
    )
    var minY: Int = NamedArea.world.bounds.minY

    @Option(
        names = ["-maxY"],
        description = ["Specifies the maximum latitude (Y coordinate) for the tile area at the minZ zoom level. Defaults to \${DEFAULT-VALUE}"]
    )
    var maxY: Int = NamedArea.world.bounds.maxY

    @Option(names = ["-h", "--help"], usageHelp = true, description = ["display this help message"])
    var help = false

    @Option(
        names = ["--epsilon"],
        description = ["Specifies the epsilon value to apply to contour lines for reducing the number of points using the Ramer–Douglas–Peucker algorithm. A higher value simplifies the lines more by removing points, while a lower value retains more detail. The default value is \${DEFAULT-VALUE}. Set to 0 to disable line simplification."]
    )
    var contourEpsilon: Int = 3

    val resolvedElevationExtent: Int
        get() = elevationExtent ?: if (dataFormat == DataSourceFormat.mapterhorn) 512 else 256

    val resolvedHillshadeRaster: Boolean
        get() = hillshadeRaster ?: (profile != ProfileName.rbt && resolvedElevationExtent == 256)

    val resolvedVectorName: String
        get() = vectorName ?: if (profile == ProfileName.rbt) "contours" else "vector"

    val resolvedPeaks: File?
        get() = peaks?.let { if (it.isEmpty()) glaciers else File(it) }

    val resolvedBbox: LonLatBounds?
        get() = bbox?.let { LonLatBounds.parse(it) }

    /** Checks combinations that picocli can't express. Throws [IllegalArgumentException] with a user-facing message. */
    fun validate() {
        require(parallelism in 1..1024) { "--parallelism must be between 1 and 1024" }
        require(shardsX > 0 && shardsY > 0) { "--shardsX and --shardsY must be positive" }
        require(glacierCacheTiles > 0) { "--glacierCacheTiles must be positive" }
        require(elevationCacheTiles > 0) { "--elevationCacheTiles must be positive" }
        require(shardWorkDir == null || resumableShards) { "--shardWorkDir requires --resumableShards" }
        require(!resumableShards || outputFormat == CliOutputFormat.mbtiles) { "--resumableShards requires --outputFormat mbtiles" }
        require(!resumableShards || pmtiles.none { it.startsWith("http://") || it.startsWith("https://") }) {
            "--resumableShards requires local PMTiles inputs so input changes can be detected"
        }
        val outputNames = buildList {
            if (terrarium) add("terrarium")
            if (resolvedHillshadeRaster) add("hillshade")
            if (vector) add(resolvedVectorName)
        }
        require(outputNames.isNotEmpty()) { "No outputs specified, nothing to do!" }
        require(outputNames.distinct().size == outputNames.size) { "Enabled outputs must have distinct names" }
        require(outputNames.all { it.isNotBlank() && File(it).name == it && it != "." && it != ".." }) {
            "--vectorName must be a filename without directory components"
        }

        when (dataFormat) {
            DataSourceFormat.srtm, DataSourceFormat.aw3d30 -> {
                require(dataDir != null) { "--data is required for --dataFormat $dataFormat" }
                require(resolvedElevationExtent == 256) { "--elevationExtent must be 256 for --dataFormat $dataFormat" }
            }
            DataSourceFormat.mapterhorn -> {
                require(pmtiles.isNotEmpty()) { "--pmtiles is required for --dataFormat mapterhorn" }
            }
        }
        require(resolvedElevationExtent == 256 || resolvedElevationExtent == 512) { "--elevationExtent must be 256 or 512" }
        if (resolvedElevationExtent != 256) {
            require(!terrarium) { "--terrarium requires --elevationExtent 256" }
            require(!resolvedHillshadeRaster) { "--hillshadeRaster requires --elevationExtent 256" }
        }
        require(peaks == null || resolvedPeaks != null) { "--peaks without a file requires --glaciers" }
        require(glaciers == null || profile == ProfileName.rbt) { "--glaciers requires --profile rbt" }
        require(glacierWkb == null || profile == ProfileName.rbt) { "--glacierWkb requires --profile rbt" }
        require(glacierWkb == null || glacierWkb!!.isFile) { "Missing glacier WKB: $glacierWkb" }
        require(rbtSmoothing == null || profile == ProfileName.rbt) { "--rbtSmoothing requires --profile rbt" }
        rbtSmoothing?.let {
            try {
                parseRbtSmoothing(it)
            } catch (e: Exception) {
                throw IllegalArgumentException("--rbtSmoothing: ${e.message}", e)
            }
        }
        bbox?.let {
            try {
                resolvedBbox
            } catch (e: Exception) {
                throw IllegalArgumentException("--bbox: ${e.message}", e)
            }
        }
    }
}

enum class DataSourceFormat {
    srtm, aw3d30, mapterhorn
}

enum class ProfileName {
    default, rbt
}

enum class CliOutputFormat {
    files,
    mbtiles
}

enum class NamedArea(val bounds: AreaBounds) {
    wholeworld(AreaBounds(minZ = 6, maxZ = 12, minX = 0, maxX = 63, minY = 0, maxY = 63)),
    world(AreaBounds(minZ = 6, maxZ = 12, minX = 0, maxX = 63, minY = 13, maxY = 43)),
    northamerica(AreaBounds(minZ = 6, maxZ = 12, minX = 0, maxX = 23, minY = 3, maxY = 26)),
    southamerica(AreaBounds(minZ = 6, maxZ = 12, minX = 17, maxX = 25, minY = 21, maxY = 43)),
    centralamerica(AreaBounds(minZ = 6, maxZ = 12, minX = 15, maxX = 18, minY = 28, maxY = 30)),
    europe(AreaBounds(minZ = 6, maxZ = 12, minX = 23, maxX = 25, minY = 5, maxY = 15)),
    newzealand(AreaBounds(minZ = 6, maxZ = 12, minX = 61, maxX = 63, minY = 38, maxY = 41)),
    australia(AreaBounds(minZ = 6, maxZ = 12, minX = 52, maxX = 59, minY = 33, maxY = 40)),
    vancouver(AreaBounds(z = 6, x = 10, y = 21, maxZ = 12)),
    deepcove(AreaBounds(z = 11, x = 324, y = 700, maxZ = 12)),
    pnw(AreaBounds(minZ = 6, maxZ = 12, minX = 9, maxX = 10, minY = 21, maxY = 22)),
    sanfrancisco(AreaBounds(z = 6, x = 10, y = 24, maxZ = 12)),
    newyork(AreaBounds(z = 6, x = 18, y = 24, maxZ = 12)),
    paris(AreaBounds(z = 6, x = 32, y = 22, maxZ = 12)),
    rome(AreaBounds(z = 6, x = 34, y = 23, maxZ = 12)),
    tokyo(AreaBounds(z = 6, x = 56, y = 25, maxZ = 12)),
    mallorca(AreaBounds(z = 6, x = 32, y = 24, maxZ = 12)),
    iceland(AreaBounds(minZ = 6, maxZ = 13, minX = 27, maxX = 29, minY = 15, maxY = 17)),
    debug(AreaBounds(minZ = 9, maxZ = 9, minX = 494, maxX = 494, minY = 328, maxY = 328))
}

class AreaBounds(
    val minZ: Int,
    val maxZ: Int,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int
) {
    constructor(maxZ: Int, z: Int, x: Int, y: Int) : this(minZ = z, maxZ = maxZ, minX = x, maxX = x, minY = y, maxY = y)

    fun applyTo(options: CliOptions) {
        options.minZ = minZ
        options.maxZ = maxZ
        options.minX = minX
        options.maxX = maxX
        options.minY = minY
        options.maxY = maxY
    }
}

/** Parses and validates the command line. Named areas override the Z/X/Y options. */
fun parseCliOptions(args: Array<String>): CliOptions {
    val options = CliOptions()
    CommandLine(options).parseArgs(*args)
    options.area?.bounds?.applyTo(options)
    if (!options.help) {
        options.validate()
    }
    return options
}

fun CliOptions.toTileRange(): TileRange {
    val bounds = resolvedBbox
    val only = resolvedTileList
    if (bounds != null && area == null) {
        val covering = TileRange.covering(bounds, minZ, maxZ)
        return if (only == null) covering else TileRange(
            covering.minZ, covering.maxZ, covering.minX, covering.maxX, covering.minY, covering.maxY, bounds, only
        )
    }
    return TileRange(
        minZ = minZ,
        maxZ = maxZ,
        minX = minX,
        maxX = maxX,
        minY = minY,
        maxY = maxY,
        bounds = bounds,
        only = only
    )
}

fun parseRbtSmoothing(spec: String?): Map<Int, RbtZoomSettings> {
    if (spec.isNullOrBlank()) {
        return mapOf()
    }
    return spec.split(",").flatMap { entry ->
        val (zoom, values) = entry.trim().split("=").also { require(it.size == 2) { "expected z=sigma/chaikin/epsilon but was '$entry'" } }
        val parts = values.split("/")
        require(parts.size == 3) { "expected sigma/chaikin/epsilon but was '$values'" }
        val settings = RbtZoomSettings(parts[0].toDouble(), parts[1].toInt(), parts[2].toDouble())
        val zooms = if (zoom.trim() == "*") (9..13).toList() else listOf(zoom.trim().toInt())
        zooms.map { it to settings }
    }.toMap()
}
