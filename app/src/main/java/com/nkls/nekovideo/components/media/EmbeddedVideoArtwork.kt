package com.nkls.nekovideo.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.nkls.nekovideo.components.helpers.XorMediaDataSource
import java.io.File
import java.io.RandomAccessFile

internal object EmbeddedVideoArtwork {
    fun fromMetadata(retriever: MediaMetadataRetriever, targetSize: Int): Bitmap? = runCatching {
        retriever.embeddedPicture?.let { decode(it, targetSize) }
    }.getOrNull()

    fun fromMatroska(file: File, targetSize: Int, xorKey: ByteArray? = null): Bitmap? = runCatching {
        if (xorKey == null) {
            RandomAccessFile(file, "r").use { input ->
                MatroskaCoverReader(input.length()) { position, length ->
                    val bytes = ByteArray(length)
                    input.seek(position)
                    input.readFully(bytes)
                    bytes
                }.readCover { decode(it, targetSize) }
            }
        } else {
            XorMediaDataSource(file, xorKey).use { input ->
                MatroskaCoverReader(input.getSize()) { position, length ->
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.readAt(position + offset, bytes, offset, length - offset)
                        check(read > 0)
                        offset += read
                    }
                    bytes
                }.readCover { decode(it, targetSize) }
            }
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray, targetSize: Int): Bitmap? {
        if (bytes.size > MatroskaCoverReader.MAX_ARTWORK_BYTES) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        // Decode near thumbnail resolution, including unusually large or panoramic covers.
        var sampleSize = 1
        val maxPixels = maxOf(1_048_576L, targetSize.toLong() * targetSize * 4)
        while ((options.outWidth / sampleSize).toLong() * (options.outHeight / sampleSize) > maxPixels) {
            sampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
