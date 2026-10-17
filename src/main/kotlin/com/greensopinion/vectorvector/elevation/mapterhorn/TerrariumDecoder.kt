package com.greensopinion.vectorvector.elevation.mapterhorn

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.PixelInterleavedSampleModel
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageInputStreamSpi
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream

object TerrariumDecoder {
    init {
        ImageIO.scanForPlugins()
    }

    /** Decodes an encoded image (webp/png) into elevations in metres, row-major with y down. */
    fun decode(bytes: ByteArray): ElevationGrid {
        // Preserve ImageIO's provider ordering, but avoid a disk-cache copy of bytes already in RAM.
        // In particular this keeps TwelveMonkeys' buffered channel stream with its memory backend.
        val stream = memoryStream(bytes)
        val image = ImageIO.read(stream)
        if (image == null) {
            // ImageIO owns/closes recognized streams, but leaves an unrecognized stream open.
            stream.close()
            throw IllegalStateException("cannot decode image (${bytes.size} bytes): no ImageIO reader, is imageio-webp registered?")
        }
        return decode(image)
    }

    private fun memoryStream(bytes: ByteArray): ImageInputStream {
        val input = ByteArrayInputStream(bytes)
        val providers = IIORegistry.getDefaultInstance().getServiceProviders(ImageInputStreamSpi::class.java, true)
        while (providers.hasNext()) {
            val provider = providers.next()
            if (provider.inputClass.isInstance(input)) {
                provider.createInputStreamInstance(input, false, null)?.let { return it }
            }
        }
        return MemoryCacheImageInputStream(input)
    }

    /** `elevation = (R * 256 + G + B / 256) - 32768`; fully transparent pixels are NaN (no data). */
    fun decode(image: BufferedImage): ElevationGrid {
        require(image.width == image.height) { "expected a square image but was ${image.width}x${image.height}" }
        val size = image.width
        fastDecode(image)?.let { return it }
        val argb = image.getRGB(0, 0, size, size, null, 0, size)
        val hasAlpha = image.colorModel.hasAlpha()
        val data = FloatArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            data[i] = if (hasAlpha && (p ushr 24) == 0) {
                Float.NaN
            } else {
                decodePixel((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }
        return ElevationGrid(size, data)
    }

    /** Reads 8-bit interleaved RGB(A) rasters directly, avoiding per-pixel colour model calls. */
    private fun fastDecode(image: BufferedImage): ElevationGrid? {
        val raster = image.raster
        val buffer = raster.dataBuffer as? DataBufferByte ?: return null
        val model = raster.sampleModel as? PixelInterleavedSampleModel ?: return null
        if (buffer.numBanks != 1 || raster.sampleModelTranslateX != 0 || raster.sampleModelTranslateY != 0) return null
        val bands = model.numBands
        if (bands != 3 && bands != 4) return null
        val offsets = model.bandOffsets
        val stride = model.pixelStride
        val scanline = model.scanlineStride
        val bytes = buffer.data
        val size = image.width
        val data = FloatArray(size * size)
        val alpha = if (bands == 4 && image.colorModel.hasAlpha()) offsets[3] else -1
        for (y in 0..<size) {
            var p = y * scanline
            for (x in 0..<size) {
                data[y * size + x] = if (alpha >= 0 && bytes[p + alpha].toInt() == 0) {
                    Float.NaN
                } else {
                    decodePixel(
                        bytes[p + offsets[0]].toInt() and 0xFF,
                        bytes[p + offsets[1]].toInt() and 0xFF,
                        bytes[p + offsets[2]].toInt() and 0xFF
                    )
                }
                p += stride
            }
        }
        return ElevationGrid(size, data)
    }

    fun decodePixel(r: Int, g: Int, b: Int): Float = (r * 256.0 + g + b / 256.0 - 32768.0).toFloat()
}
