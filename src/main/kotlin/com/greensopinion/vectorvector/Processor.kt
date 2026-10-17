package com.greensopinion.vectorvector

import com.greensopinion.vectorvector.metrics.MetricsProvider
import com.greensopinion.vectorvector.metrics.Progress
import com.greensopinion.vectorvector.metrics.toLogString
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class Processor(
    private val tileRange: TileRange,
    private val sink: TileSink,
    private val metricsProvider: MetricsProvider,
    private val parallelism: Int = 2,
    private val log :KLogger =  KotlinLogging.logger {  }
) {
    private val progress = Progress(tileRange.size)

    fun process() = runBlocking {
        log.info { "Processing area of z=${tileRange.minZ}-${tileRange.maxZ} x=${tileRange.minX}-${tileRange.maxX} y=${tileRange.minY}-${tileRange.maxY} (${tileRange.size} tiles)" }
        require(parallelism > 0) { "parallelism must be positive" }
        coroutineScope {
            val queue = Channel<TileId>(parallelism * 2)
            repeat(parallelism) {
                launch(Dispatchers.IO) {
                    for (tileId in queue) {
                        metricsProvider.get().addCount("Processor.tile")
                        try {
                            progress.completedOne(sink.accept(Tile(tileId)))
                        } catch (e: Exception) {
                            throw IllegalStateException("Failed tile $tileId", e)
                        }
                    }
                }
            }
            for (tile in tileRange.tiles()) queue.send(tile)
            queue.close()
        }
        log.info { "Completed in ${progress.elapsed.toLogString()}" }
    }
}
