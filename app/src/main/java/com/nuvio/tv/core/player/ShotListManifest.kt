package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.core.util.DebugSwitches
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What identifies a file to a playback manifest (hdr-analyze `docs/PLAYBACK_MANIFEST.md`): its size, the
 * SHA-256 of its first and last MiB, and the `lookup_key` over the three, which names the manifest on a
 * plain HTTP folder (`<lookup_key>.dvmanifest.json`). It makes the manifest a candidate only: the track
 * check and the AU matcher confirm it.
 */
internal class ManifestSourceIdentity(
    val sizeBytes: Long,
    val headSha256: ByteArray,
    val tailSha256: ByteArray,
) {
    /** SHA-256 of the size as a big-endian u64, the head digest and the tail digest: 72 bytes. */
    val lookupKey: ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(ByteArray(8) { i -> (sizeBytes ushr (8 * (7 - i))).toByte() })
        update(headSha256)
        update(tailSha256)
        digest()
    }

    val lookupKeyHex: String get() = lookupKey.toHex()

    companion object {
        /** `CHUNK_BYTES` of hdr-analyze: the head and the tail are 1 MiB each. */
        const val CHUNK_BYTES = 1 shl 20

        /**
         * Reads the identity of [uri] through [dataSourceFactory] (the playback's own stack, so its
         * headers and redirects apply): one read from the start, which gives the size and the head, and
         * one range read of the tail when the file is longer than one chunk. A server that sends the
         * whole file without a length (chunked) gets a range request for the head instead, and the size
         * comes from its `Content-Range`. Null when the size stays unknown or the file ends early.
         */
        @UnstableApi
        @Throws(IOException::class)
        fun read(dataSourceFactory: DataSource.Factory, uri: Uri): ManifestSourceIdentity? {
            val source = dataSourceFactory.createDataSource()
            var size: Long
            var head: ByteArray? = null
            try {
                size = source.open(DataSpec(uri))
                if (size >= 0) head = readExactly(source, minOf(size, CHUNK_BYTES.toLong()).toInt()) ?: return null
            } finally {
                source.close()
            }
            if (head == null) {
                val ranged = dataSourceFactory.createDataSource()
                try {
                    val length = ranged.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(CHUNK_BYTES.toLong()).build())
                    size = totalOf(ranged.responseHeaders) ?: return null
                    val headLength = minOf(size, CHUNK_BYTES.toLong()).toInt()
                    if (length != C.LENGTH_UNSET.toLong() && length < headLength) return null
                    head = readExactly(ranged, headLength) ?: return null
                } finally {
                    ranged.close()
                }
            }
            val tail = if (size <= CHUNK_BYTES) {
                head
            } else {
                val tailSource = dataSourceFactory.createDataSource()
                try {
                    tailSource.open(
                        DataSpec.Builder().setUri(uri).setPosition(size - CHUNK_BYTES).setLength(CHUNK_BYTES.toLong()).build()
                    )
                    readExactly(tailSource, CHUNK_BYTES) ?: return null
                } finally {
                    tailSource.close()
                }
            }
            return ManifestSourceIdentity(size, sha256(head), sha256(tail))
        }

        /** The complete length in a `Content-Range: bytes <first>-<last>/<total>` header, or null. */
        internal fun totalOf(headers: Map<String, List<String>>): Long? {
            val value = headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
                ?: return null
            return value.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it >= 0 }
        }

        private fun readExactly(source: DataSource, length: Int): ByteArray? {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = source.read(bytes, offset, length - offset)
                if (read == C.RESULT_END_OF_INPUT) return null
                offset += read
            }
            return bytes
        }

        internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** A manifest fetched for one file, with the identity it was fetched by. */
internal class FetchedManifest(val identity: ManifestSourceIdentity, val json: ByteArray)

/**
 * The playback manifests of one player (ROADMAP DV4.1): each file's manifest is fetched by its
 * `lookup_key` from [baseUrl], a folder that holds `<lookup_key>.dvmanifest.json` files: an HTTP folder
 * (the owner's Oracle host) or a local one (`file:///sdcard/...`). [forUri] starts the fetch when the extractor is created, so it runs while the
 * extractor reads up to the Tracks; the track's RPU source then waits at most [waitMs] for it. The last
 * file's fetch is kept: a new extractor for the same URI reuses it.
 *
 * The identity reads go through [playbackDataSourceFactory]; the manifest itself through
 * [manifestDataSourceFactory], without the stream's headers, which belong to the stream's host.
 */
@UnstableApi
internal class ShotListManifests(
    private val baseUrl: String,
    private val playbackDataSourceFactory: DataSource.Factory,
    private val manifestDataSourceFactory: DataSource.Factory,
    private val waitMs: Long = DEFAULT_WAIT_MS,
    private val executor: ExecutorService = EXECUTOR,
) {
    private var lastUri: String? = null
    private var last: Pending? = null

    /** The fetch of [uri]'s manifest, started now unless the last call was for the same URI. */
    @Synchronized
    fun forUri(uri: Uri): Pending {
        val key = uri.toString()
        last?.let { if (key == lastUri) return it }
        val future = executor.submit<FetchedManifest?> { fetch(uri) }
        return Pending(future, waitMs).also {
            lastUri = key
            last = it
        }
    }

    private fun fetch(uri: Uri): FetchedManifest? {
        val started = System.nanoTime()
        val identity = try {
            ManifestSourceIdentity.read(playbackDataSourceFactory, uri)
        } catch (e: IOException) {
            log("the file's identity could not be read: ${e.message}")
            return null
        } ?: return null.also { log("the file's size is unknown; no manifest lookup") }
        val url = manifestUrl(baseUrl, identity.lookupKeyHex)
        val json = try {
            download(manifestDataSourceFactory, Uri.parse(url))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            log("no manifest for lookup ${identity.lookupKeyHex.take(12)} (HTTP ${e.responseCode})")
            return null
        } catch (e: IOException) {
            log("the manifest for lookup ${identity.lookupKeyHex.take(12)} could not be fetched: ${e.message}")
            return null
        } ?: return null.also { log("the manifest for lookup ${identity.lookupKeyHex.take(12)} is too long") }
        val ms = (System.nanoTime() - started) / 1_000_000
        log("manifest for lookup ${identity.lookupKeyHex.take(12)}: ${json.size} bytes in $ms ms")
        return FetchedManifest(identity, json)
    }

    /**
     * One file's fetch. Once a track drops the manifest ([markDropped]: a rejection or a native failure),
     * it is dropped for the rest of the playback: a track prepared again, or a new extractor for the same
     * file, gets no manifest.
     */
    class Pending internal constructor(private val future: Future<FetchedManifest?>, private val waitMs: Long) {
        @Volatile
        var dropped = false
            private set

        fun markDropped() {
            dropped = true
        }

        /** The manifest, or null when there is none, it was dropped, it failed, or it is not there within the wait. */
        fun await(): FetchedManifest? = if (dropped) null else try {
            future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // The track keeps its fallback for this playback: stop the fetch.
            future.cancel(true)
            log("the manifest is not ready after $waitMs ms; the track keeps its fallback")
            null
        } catch (e: java.util.concurrent.CancellationException) {
            null
        } catch (e: ExecutionException) {
            log("the manifest fetch failed: ${e.cause?.message}")
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    companion object {
        /**
         * `adb shell setprop debug.nuvio.dv_manifest_url http://<host>:<port>/<folder>` (or a `file://`
         * folder): converted HLG tracks take their L1 from that folder's playback manifests. It wins over
         * the saved player setting (DV4.8); with neither: no lookup, no request.
         */
        const val URL_SWITCH = "debug.nuvio.dv_manifest_url"

        /** `debug.nuvio.dv_manifest_wait_ms`: how long track init waits for the manifest. */
        const val WAIT_SWITCH = "debug.nuvio.dv_manifest_wait_ms"

        const val DEFAULT_WAIT_MS = 3_000L

        private const val TAG = "DvShotList"
        private const val TIMEOUT_MS = 5_000

        // One thread per fetch: a fetch stalled on a slow stream must not hold up the next file's.
        private val EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "dv-manifest").apply { isDaemon = true }
        }

        /**
         * The manifests [URL_SWITCH] names, else the saved folder when [savedEnabled] ([DvManifestFolder]),
         * or null when neither gives one ([playbackDataSourceFactory] is then not called).
         */
        fun fromSwitches(
            context: android.content.Context,
            savedEnabled: Boolean,
            savedUrl: String,
            playbackDataSourceFactory: () -> DataSource.Factory,
        ): ShotListManifests? {
            val base = DvManifestFolder.resolve(DebugSwitches.property(URL_SWITCH), savedEnabled, savedUrl) ?: return null
            val wait = DebugSwitches.int(WAIT_SWITCH)?.takeIf { it >= 0 }?.toLong() ?: DEFAULT_WAIT_MS
            log("manifest folder from ${if (base.fromSetting) "the player setting" else URL_SWITCH}")
            return ShotListManifests(base.url, playbackDataSourceFactory(), manifestFactory(context), waitMs = wait)
        }

        internal fun manifestUrl(baseUrl: String, lookupKeyHex: String) =
            "${baseUrl.trimEnd('/')}/$lookupKeyHex.dvmanifest.json"

        /** The body of [uri], or null when it is longer than the library takes. */
        internal fun download(factory: DataSource.Factory, uri: Uri): ByteArray? {
            val source = factory.createDataSource()
            try {
                val length = source.open(DataSpec(uri))
                if (length > DoviRpuBuilder.MAX_MANIFEST_BYTES) return null
                val out = ByteArrayOutputStream(if (length in 1..DoviRpuBuilder.MAX_MANIFEST_BYTES) length.toInt() else 64 * 1024)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (out.size() + read > DoviRpuBuilder.MAX_MANIFEST_BYTES) return null
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            } finally {
                source.close()
            }
        }

        /** File, content and HTTP URLs; HTTP without the stream's headers. */
        private fun manifestFactory(context: android.content.Context): DataSource.Factory = DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(TIMEOUT_MS)
                .setReadTimeoutMs(TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true),
        )

        private fun log(message: String) {
            // android.util.Log: Media3's Log throws in JVM unit tests.
            android.util.Log.i(TAG, "DV_MANIFEST: $message")
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
