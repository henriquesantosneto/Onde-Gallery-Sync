package com.onegallery.sync.ui.gallery

import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.DataSource
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import java.io.File
import okio.BufferedSource
import okio.Path.Companion.toOkioPath

/** JPEG de miniatura no disco, mesmo quando o nome termina em `.mp4`. */
internal data class DiskThumb(val file: File)

/**
 * O FileFetcher do Coil trata `.mp4` como vídeo. Este fetcher força imagem,
 * para a rolagem não abrir o clipe nem derrubar o processo.
 */
internal class DiskThumbFetcher(
    private val file: File,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        if (!file.isSafeGridImageFile()) error("miniatura indisponível")
        return SourceResult(
            source = ImageSource(file = file.toOkioPath()),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<DiskThumb> {
        override fun create(
            data: DiskThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = DiskThumbFetcher(data.file)
    }
}

/**
 * Miniatura de vídeo é JPEG gravado com o nome do clipe (`.mp4`).
 * Sem este decoder o Coil manda o arquivo ao VideoFrameDecoder e a rolagem fecha o app.
 */
internal class CachedThumbImageDecoderFactory : Decoder.Factory {
    override fun create(
        result: SourceResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        val mime = result.mimeType
        if (mime != null && mime.startsWith("image/")) return null
        if (!result.source.source().startsWithImageMagic()) return null
        return BitmapFactoryDecoder(result.source, options)
    }
}

private fun BufferedSource.startsWithImageMagic(): Boolean {
    val peek = peek()
    if (!peek.request(3)) return false
    val count = when {
        peek.request(12) -> 12
        peek.request(8) -> 8
        else -> 3
    }
    val header = ByteArray(count)
    return runCatching {
        peek.readFully(header)
        header.hasImageMagic()
    }.getOrDefault(false)
}

internal fun ByteArray.hasImageMagic(): Boolean {
    if (size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (size >= 8 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte()) return true
    if (size >= 6 && this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte()) return true
    if (size >= 12 &&
        this[0] == 'R'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[8] == 'W'.code.toByte()
    ) {
        return true
    }
    return false
}
