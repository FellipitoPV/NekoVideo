package com.nkls.nekovideo.recoverylab

import com.nkls.nekovideo.components.helpers.PortableVideoStore

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/** Exposes only the original media bytes; the recovery trailer never reaches the decoder. */
class RecoveryLabMediaDataSource(file: File, private val metadata: PortableVideoStore.Metadata) : MediaDataSource() {
    private val input = RandomAccessFile(file, "r")
    private val key = Base64.getDecoder().decode(metadata.contentKey)

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= metadata.payloadSize) return -1
        val count = minOf(size.toLong(), metadata.payloadSize - position).toInt()
        input.seek(position)
        input.readFully(buffer, offset, count)
        PortableVideoStore.xor(buffer, offset, count, position, key)
        return count
    }

    override fun getSize() = metadata.payloadSize
    @Synchronized override fun close() { try { input.close() } finally { key.fill(0) } }
}

@androidx.annotation.OptIn(UnstableApi::class)
class RecoveryLabDataSource(
    private val file: File,
    private val metadata: PortableVideoStore.Metadata
) : DataSource {
    private var source: RecoveryLabMediaDataSource? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        require(dataSpec.position in 0..metadata.payloadSize)
        source?.close()
        source = RecoveryLabMediaDataSource(file, metadata)
        position = dataSpec.position
        remaining = metadata.payloadSize - position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = checkNotNull(source).readAt(position, buffer, offset,
            minOf(length.toLong(), remaining).toInt())
        if (count > 0) { position += count; remaining -= count }
        return count
    }
    override fun getUri(): Uri = Uri.fromFile(file)
    override fun close() { source?.close(); source = null }
    override fun addTransferListener(transferListener: TransferListener) = Unit
}
