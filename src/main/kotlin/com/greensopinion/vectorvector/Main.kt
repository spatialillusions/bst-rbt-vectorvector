package com.greensopinion.vectorvector

import com.greensopinion.vectorvector.cli.CliOptions
import com.greensopinion.vectorvector.cli.CliOutputFormat
import com.greensopinion.vectorvector.cli.DataSourceFormat
import com.greensopinion.vectorvector.cli.ProfileName
import com.greensopinion.vectorvector.cli.parseCliOptions
import com.greensopinion.vectorvector.cli.parseRbtSmoothing
import com.greensopinion.vectorvector.elevation.Aw3d30ElevationDataStore
import com.greensopinion.vectorvector.elevation.BlockElevationDataStore
import com.greensopinion.vectorvector.elevation.BlockOffsetMapper
import com.greensopinion.vectorvector.elevation.BlockStore
import com.greensopinion.vectorvector.elevation.CachingBlockStore
import com.greensopinion.vectorvector.elevation.CachingElevationDataStore
import com.greensopinion.vectorvector.elevation.DegreeBlockMapper
import com.greensopinion.vectorvector.elevation.Degrees
import com.greensopinion.vectorvector.elevation.ElevationDataStore
import com.greensopinion.vectorvector.elevation.SrtmBlockMapper
import com.greensopinion.vectorvector.elevation.SrtmBlockStore
import com.greensopinion.vectorvector.elevation.mapterhorn.MapterhornElevationDataStore
import com.greensopinion.vectorvector.elevation.mapterhorn.MapterhornSourceSet
import com.greensopinion.vectorvector.metrics.MetricsProvider
import com.greensopinion.vectorvector.osm.GlacierIceSplitter
import com.greensopinion.vectorvector.osm.GlacierIndex
import com.greensopinion.vectorvector.osm.GlacierLoader
import com.greensopinion.vectorvector.osm.GlacierWkbLoader
import com.greensopinion.vectorvector.osm.PeakIndex
import com.greensopinion.vectorvector.osm.PeakLoader
import com.greensopinion.vectorvector.osm.Peak
import com.greensopinion.vectorvector.osm.PreparedSpatialStore
import com.greensopinion.vectorvector.sink.contour.IceSplitter
import com.greensopinion.vectorvector.pmtiles.PmtilesReader
import com.greensopinion.vectorvector.pmtiles.rangeSourceFor
import com.greensopinion.vectorvector.profile.ContourProfile
import com.greensopinion.vectorvector.profile.DefaultProfile
import com.greensopinion.vectorvector.profile.RbtProfile
import com.greensopinion.vectorvector.metrics.PeriodicMetrics
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import com.greensopinion.vectorvector.repository.FilesystemTileRepository
import com.greensopinion.vectorvector.repository.MbtilesMetadata
import com.greensopinion.vectorvector.repository.MbtilesTileRepository
import com.greensopinion.vectorvector.repository.TileRepository
import com.greensopinion.vectorvector.sink.HillshadeRasterSink
import com.greensopinion.vectorvector.sink.TerrariumSink
import com.greensopinion.vectorvector.sink.VectorTileSink
import com.greensopinion.vectorvector.sink.hillshade.ResolutionPerPixel
import com.greensopinion.vectorvector.sink.LayerSchema
import com.greensopinion.vectorvector.sink.vectorSchema
import com.greensopinion.vectorvector.util.closeSafely
import io.github.oshai.kotlinlogging.KotlinLogging
import picocli.CommandLine
import java.io.Closeable
import java.io.File
import java.time.Duration
import kotlin.system.exitProcess

private val log = KotlinLogging.logger { }

fun main(args: Array<String>) {
    val options = try {
        parseCliOptions(args)
    } catch (e: Exception) {
        System.err.println(e.message)
        CommandLine.usage(CliOptions(), System.err)
        exitProcess(2)
    }
    if (options.help) {
        CommandLine.usage(CliOptions(), System.out)
        return
    }
    val metricsProvider = SingletonMetricsProvider()
    val profile = contourProfile(options)
    val dataStore = cachingElevationDataStore(options, metricsProvider)

    val spatial = options.spatialData?.let { folder ->
        PreparedSpatialStore(folder, { records -> createPeakIndex(options, dataStore, records) },
            options.glacierCacheTiles, options.glacierCacheMiB * 1048576).also {
            it.validateSource("glaciers", options.glaciers)
            it.validateSource("ice", options.glacierWkb)
            it.validateSource("peaks", options.resolvedPeaks)
        }
    }
    val peaks by lazy { peakIndex(options, dataStore) }
    val ice by lazy { iceSplitterProvider(options) }
    try {
        PeriodicMetrics(interval = Duration.ofSeconds(30), metrics = metricsProvider.metrics).use {
            runTileBuild(options, metricsProvider, { buildIdentity(options, args) },
                tileRepositoryFactory(options, metricsProvider),
                afterShard = { if (options.reopenArchivesAfterShard) dataStore.afterShard() }) { factory ->
                tileSinkToRepository(options, profile, dataStore, metricsProvider, factory,
                    { peaks }, { ice }, spatial)
            }
        }
    } finally {
        (dataStore as? Closeable)?.close()
    }
}

private fun tileRepositoryFactory(
    options: CliOptions,
    metricsProvider: SingletonMetricsProvider
): RepositoryFactory =
    if (options.outputFormat == CliOutputFormat.mbtiles) {
        { name, format, layers ->
            createMbTilesRepository(
                options,
                format,
                File(options.outputDir, "$name.mbtiles"),
                metricsProvider,
                layers
            )
        }
    } else {
        { _, _, _ -> FilesystemTileRepository(options.outputDir) }
    }

private fun cachingElevationDataStore(
    options: CliOptions,
    metricsProvider: SingletonMetricsProvider
): ElevationDataStore {
    val blockSize: Degrees
    val blockMapper: BlockOffsetMapper
    val store: BlockStore
    val cacheSize: Long
    if (options.dataFormat == DataSourceFormat.mapterhorn) {
        val readers = options.pmtiles.map { PmtilesReader(rangeSourceFor(it, options.pmtilesCacheDir)) }
        readers.forEach {
            val h = it.header
            log.info { "${it.name}: z${h.minZoom}-${h.maxZoom} ${h.tileType} bounds=${h.minLon},${h.minLat},${h.maxLon},${h.maxLat}" }
        }
        return MapterhornElevationDataStore(
            sources = MapterhornSourceSet(readers, cacheSize = options.elevationCacheTiles, cacheBytes = options.elevationCacheMiB * 1048576 / 3),
            extent = options.resolvedElevationExtent,
            metricsProvider = metricsProvider,
            cacheSize = options.elevationCacheTiles,
            cacheBytes = options.elevationCacheMiB * 1048576 * 2 / 3
        )
    }
    if (options.dataFormat == DataSourceFormat.srtm) {
        blockSize = Degrees(5.0)
        store = SrtmBlockStore(
            folder = options.dataDir!!,
            metricsProvider = metricsProvider
        )
        blockMapper = SrtmBlockMapper(store.blockExtent, blockSize)
        cacheSize = 25
    } else {
        blockSize = Degrees(1.0)
        store = Aw3d30ElevationDataStore(
            dataFolder = options.dataDir!!,
            outputFolder = options.dataDir!!,
            metricsProvider = metricsProvider
        )
        blockMapper = DegreeBlockMapper(store.blockExtent, blockSize)
        cacheSize = 200L
    }
    val tileExtent = 256
    return CachingElevationDataStore(
        BlockElevationDataStore(
            blockMapper = blockMapper,
            tileExtent = tileExtent,
            blockStore = CachingBlockStore(
                cacheSize,
                store,
                metricsProvider
            )
        )
    )
}

private fun tileSinkToRepository(
    options: CliOptions,
    profile: ContourProfile,
    dataStore: ElevationDataStore,
    metricsProvider: SingletonMetricsProvider,
    repositoryFactory: RepositoryFactory,
    peaksProvider: () -> PeakIndex?,
    iceProvider: () -> ((TileId) -> IceSplitter?),
    spatial: PreparedSpatialStore? = null
): Map<TileSink, TileRepository> {
    val tileExtent = 256
    val sinks = mutableMapOf<TileSink, TileRepository>()
    val repositories = mutableListOf<TileRepository>()
    val trackedFactory: RepositoryFactory = { name, format, layers ->
        repositoryFactory(name, format, layers).also { repositories.add(it) }
    }
    try {
        if (options.terrarium) {
            val repository = trackedFactory("terrarium", "png", listOf())
            sinks[TerrariumSink(
                extent = tileExtent,
                repository = repository,
                elevationDataStore = dataStore,
                metricsProvider = metricsProvider
            )] = repository
        }
        if (options.resolvedHillshadeRaster) {
            val repository = trackedFactory("hillshade", "png", listOf())
            sinks[HillshadeRasterSink(
                extent = tileExtent,
                repository = repository,
                elevationDataStore = dataStore,
                resolutionPerPixel = ResolutionPerPixel(tileExtent = tileExtent, minZ = options.minZ, maxZ = options.maxZ),
                metricsProvider = metricsProvider
            )] = repository
        }
        if (options.vector) {
            val peaks = if (spatial == null) peaksProvider() else null
            val repository = trackedFactory(
                options.resolvedVectorName, "pbf",
                listOfNotNull(profile.contourLayer(options.minZ, options.maxZ), spatial?.peakSchema ?: peaks?.schema())
            )
            sinks[VectorTileSink(
                contourOptionsProvider = { tile -> profile.contourOptions(tile.id) },
                iceSplitterProvider = if (spatial == null) iceProvider() else { id -> spatial.ice(id) },
                gzip = options.resolvedGzipTiles,
                extraLayers = if (spatial != null) listOf({ tile -> spatial.peaks(tile) }) else
                    listOfNotNull(peaks?.let { index -> { tile: TileId -> index.layer(tile) } }),
                repository = repository,
                elevationDataStore = dataStore,
                metricsProvider = metricsProvider
            )] = repository
        }
        require(sinks.isNotEmpty()) { "No outputs specified, nothing to do!" }
        return sinks
    } catch (e: Throwable) {
        try { closeSafely(repositories) } catch (closing: Throwable) { e.addSuppressed(closing) }
        throw e
    }
}

/** Vector layers written into a pbf repository; empty for raster outputs. */
typealias RepositoryFactory = (name: String, format: String, layers: List<LayerSchema>) -> TileRepository

fun createMbTilesRepository(
    options: CliOptions,
    format: String,
    file: File,
    metricsProvider: MetricsProvider,
    layers: List<LayerSchema> = listOf()
): TileRepository =
    MbtilesTileRepository(file, metricsProvider).also { repository ->
        try {
            val minZoom = layers.minOfOrNull { it.minZoom } ?: options.minZ
            val maxZoom = layers.maxOfOrNull { it.maxZoom } ?: options.maxZ
            val rbt = options.profile == ProfileName.rbt
            repository.setMetadata(
                MbtilesMetadata(
                    name = if (rbt) "RBT contours" else "vectorvector $format tiles",
                    format = format,
                    json = if (format == "pbf") vectorSchema(minZoom, maxZoom, layers) else "",
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    bounds = options.resolvedBbox?.toString() ?: "-180.0,-85,180,85",
                    attribution = attribution(options),
                    description = if (rbt) "Contour lines (ele, index, is_ice) matching the RBT contours layer" else null
                )
            )
        } catch (e: Exception) {
            repository.close()
            throw e
        }
    }

private fun attribution(options: CliOptions): String? {
    val parts = mutableListOf<String>()
    if (options.dataFormat == DataSourceFormat.mapterhorn) {
        parts += "<a href=\"https://mapterhorn.com/attribution\">© Mapterhorn</a>"
    }
    if (options.glaciers != null || options.glacierWkb != null || options.resolvedPeaks != null || options.spatialData != null) {
        parts += "<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"
    }
    return parts.joinToString(" ").ifEmpty { null }
}

private fun peakIndex(options: CliOptions, dataStore: ElevationDataStore): PeakIndex? {
    val file = options.resolvedPeaks ?: return null
    return createPeakIndex(options, dataStore, PeakLoader(file).load()).also {
        log.info { "Peaks layer: ${it.size} peaks from ${file.name}" }
    }
}

private fun createPeakIndex(options: CliOptions, dataStore: ElevationDataStore, records: List<Peak>): PeakIndex {
    val demZoom = 12
    return PeakIndex(
        peaks = records,
        minZoom = maxOf(options.peaksMinZoom, options.minZ),
        maxZoom = if (options.profile == ProfileName.rbt) minOf(options.maxZ, 13) else options.maxZ,
        demElevation = { lon, lat ->
            val tile = WebMercator.tileAt(lon, lat, demZoom)
            val elevation = dataStore.get(tile)
            val px = ((WebMercator.lonToTileX(lon, demZoom) - tile.x) * elevation.extent).toInt().coerceIn(0, elevation.extent - 1)
            val py = ((WebMercator.latToTileY(lat, demZoom) - tile.y) * elevation.extent).toInt().coerceIn(0, elevation.extent - 1)
            elevation.get(px, py).takeIf { it.valid }?.meters
        }
    )
}

private fun iceSplitterProvider(options: CliOptions): (TileId) -> IceSplitter? {
    if (options.glaciers == null && options.glacierWkb == null) return { null }
    val start = System.currentTimeMillis()
    val glaciers = buildList {
        options.glaciers?.let { addAll(GlacierLoader(it).load()) }
        options.glacierWkb?.let { addAll(GlacierWkbLoader(it).load()) }
    }
    val index = GlacierIndex(glaciers, cacheSize = options.glacierCacheTiles, cacheBytes = options.glacierCacheMiB * 1048576)
    val sources = listOfNotNull(options.glaciers, options.glacierWkb).joinToString(" + ") { it.name }
    log.info { "Loaded ${index.size} glaciers from $sources in ${System.currentTimeMillis() - start} ms" }
    return { tile -> index.forTile(tile)?.let { GlacierIceSplitter(it) } }
}

private fun contourProfile(options: CliOptions): ContourProfile = when (options.profile) {
    ProfileName.default -> DefaultProfile(epsilon = options.contourEpsilon)
    ProfileName.rbt -> RbtProfile(settings = RbtProfile.DEFAULT_SETTINGS + parseRbtSmoothing(options.rbtSmoothing))
}
