package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.RandomAccessFile

/**
 * Custom DataSource for ExoPlayer that reverses XOR on the first 8KB of locked video files.
 * Allows seamless playback of locked content without creating temporary decrypted files.
 */
@OptIn(UnstableApi::class)
class LockedFolderDataSource(
    private val xorKey: ByteArray
) : DataSource {

    companion object {
        private const val HEADER_SIZE = 8192L // 8KB
    }

    private var randomAccessFile: RandomAccessFile? = null
    private var uri: Uri? = null
    private var currentPosition: Long = 0
    private var bytesRemaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val filePath = dataSpec.uri.path ?: throw IllegalArgumentException("Invalid file URI")
        val file = File(filePath)

        if (!file.exists()) {
            throw java.io.FileNotFoundException("File not found: $filePath")
        }

        randomAccessFile = RandomAccessFile(file, "r")
        val fileLength = randomAccessFile!!.length()

        val position = if (dataSpec.position != 0L) dataSpec.position else 0L
        currentPosition = position
        randomAccessFile!!.seek(position)

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            fileLength - position
        }

        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT

        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytesRead = raf.read(buffer, offset, bytesToRead)

        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        // If we're reading within the first 8KB, apply XOR to reverse the obfuscation
        if (currentPosition < HEADER_SIZE) {
            val headerEnd = minOf(currentPosition + bytesRead, HEADER_SIZE)
            val xorCount = (headerEnd - currentPosition).toInt()

            for (i in 0 until xorCount) {
                val filePos = (currentPosition + i).toInt()
                val bufferIdx = offset + i
                buffer[bufferIdx] = (buffer[bufferIdx].toInt() xor xorKey[filePos % xorKey.size].toInt()).toByte()
            }
        }

        currentPosition += bytesRead
        bytesRemaining -= bytesRead

        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        randomAccessFile?.close()
        randomAccessFile = null
        uri = null
        currentPosition = 0
        bytesRemaining = 0
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // Not needed for local file access
    }
}

/**
 * Factory for creating LockedFolderDataSource instances with the XOR key injected.
 */
@OptIn(UnstableApi::class)
class LockedFolderDataSourceFactory(
    private val xorKey: ByteArray
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return LockedFolderDataSource(xorKey)
    }
}

/**
 * Hybrid DataSource that handles both normal (file://) and locked (locked://) URIs.
 * For locked URIs: uses LockedFolderDataSource with XOR decryption.
 * For normal URIs: delegates to DefaultDataSource for standard playback.
 */
@OptIn(UnstableApi::class)
class HybridDataSource(
    private val context: Context
) : DataSource {

    private var activeDelegate: DataSource? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val uriString = dataSpec.uri.toString()
        val isLocked = uriString.startsWith("locked://")

        if (isLocked) {
            val filePath = uriString.removePrefix("locked://")
            // Look up the correct XOR key for this file's folder (supports multi-folder playlists)
            val xorKey = LockedPlaybackSession.getXorKeyForFile(filePath)
            if (xorKey != null) {
                val lockedDs = LockedFolderDataSource(xorKey)
                // Convert locked:// to file:// for actual file access
                val fileSpec = dataSpec.buildUpon()
                    .setUri(Uri.parse("file://$filePath"))
                    .build()
                activeDelegate = lockedDs
                uri = dataSpec.uri
                return lockedDs.open(fileSpec)
            }
        }

        // Normal file - use default DataSource
        val defaultDs = DefaultDataSource(context, false)
        activeDelegate = defaultDs
        uri = dataSpec.uri
        return defaultDs.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeDelegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        activeDelegate?.close()
        activeDelegate = null
        uri = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // Handled by delegates
    }
}

/**
 * Factory for HybridDataSource - handles both normal and locked content seamlessly.
 */
@OptIn(UnstableApi::class)
class HybridDataSourceFactory(
    private val context: Context
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return HybridDataSource(context)
    }
}
