package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The manifest lookup of ROADMAP DV4.1: the `lookup_key` of hdr-analyze `docs/PLAYBACK_MANIFEST.md` from a
 * size lookup and two 1 MiB reads, and the fetch of `<lookup_key>.dvmanifest.json`. The expected digests
 * were computed with Python's hashlib, independently of this code.
 */
class ShotListManifestTest {

    /** Serves [files] by URI string, honouring position and length; records every open. */
    private class Files(private val files: Map<String, ByteArray>, private val knownLength: Boolean = true) : DataSource.Factory {
        val opens = mutableListOf<String>()

        override fun createDataSource(): DataSource = object : BaseDataSource(false) {
            private var bytes = ByteArray(0)
            private var position = 0
            private var end = 0
            private var uri: Uri? = null

            override fun open(dataSpec: DataSpec): Long {
                val key = dataSpec.uri.toString()
                opens += "$key@${dataSpec.position}+${dataSpec.length}"
                bytes = files[key] ?: throw IOException("404 $key")
                uri = dataSpec.uri
                position = dataSpec.position.toInt()
                end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) bytes.size else minOf(bytes.size, position + dataSpec.length.toInt())
                return if (knownLength) (end - position).toLong() else C.LENGTH_UNSET.toLong()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= end) return C.RESULT_END_OF_INPUT
                val count = minOf(length, end - position)
                System.arraycopy(bytes, position, buffer, offset, count)
                position += count
                return count
            }

            override fun getUri(): Uri? = uri

            override fun close() {}
        }
    }

    private val mib = 1 shl 20
    private val big = ByteArray(3 * mib + 17) { (it % 251).toByte() }
    private val hello = "hello".toByteArray()

    @Before
    fun uris() {
        // The JVM stub Uri keeps nothing; these tests only need the string back.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val text = firstArg<String>()
            mockk<Uri> { every { this@mockk.toString() } returns text }
        }
    }

    @After
    fun unmock() = unmockkStatic(Uri::class)

    @Test
    fun `the lookup key hashes the size as a big-endian u64 and the two raw digests`() {
        val identity = ManifestSourceIdentity.read(Files(mapOf("f" to big)), Uri.parse("f"))!!
        assertEquals(big.size.toLong(), identity.sizeBytes)
        assertEquals("631b84027d6b9e52b539c4e8373622d23032dfadc64d60af87339c9037e4f769", identity.headSha256.toHex())
        assertEquals("9267b9ea1c2bd185019a5faf66afb0b57ebcc2b6417eb4f130e2c64c79500a25", identity.tailSha256.toHex())
        assertEquals("9cf1c9b7fcf96ecb9cf75e4dc4a20c66b60f084d044a5a80bf334ef05d5c18ed", identity.lookupKeyHex)
    }

    @Test
    fun `a file of one chunk or less is read once and its head is its tail`() {
        val files = Files(mapOf("f" to hello))
        val identity = ManifestSourceIdentity.read(files, Uri.parse("f"))!!
        assertEquals(listOf("f@0+-1"), files.opens)
        assertArrayEquals(identity.headSha256, identity.tailSha256)
        assertEquals("9bffb41b7848bdd658c178d4406e85771778622e08e329311931dc5da7747cbe", identity.lookupKeyHex)
    }

    @Test
    fun `a longer file takes one read from the start and one range read of the last MiB`() {
        val files = Files(mapOf("f" to big))
        ManifestSourceIdentity.read(files, Uri.parse("f"))
        assertEquals(listOf("f@0+-1", "f@${big.size - mib}+$mib"), files.opens)
    }

    @Test
    fun `a server that sends the whole file chunked still gives the size of a range request's Content-Range`() {
        val chunked = object : DataSource.Factory {
            override fun createDataSource(): DataSource = object : BaseDataSource(true) {
                private var position = 0
                private var end = 0
                private var ranged = false

                override fun open(dataSpec: DataSpec): Long {
                    position = dataSpec.position.toInt()
                    ranged = dataSpec.length != C.LENGTH_UNSET.toLong()
                    end = if (ranged) position + dataSpec.length.toInt() else big.size
                    return if (ranged) dataSpec.length else C.LENGTH_UNSET.toLong()
                }

                override fun getResponseHeaders(): Map<String, List<String>> =
                    if (ranged) mapOf("Content-Range" to listOf("bytes $position-${end - 1}/${big.size}")) else emptyMap()

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= end) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, end - position)
                    System.arraycopy(big, position, buffer, offset, count)
                    position += count
                    return count
                }

                override fun getUri(): Uri? = null
                override fun close() {}
            }
        }
        val identity = ManifestSourceIdentity.read(chunked, Uri.parse("f"))!!
        assertEquals(big.size.toLong(), identity.sizeBytes)
        assertEquals("9cf1c9b7fcf96ecb9cf75e4dc4a20c66b60f084d044a5a80bf334ef05d5c18ed", identity.lookupKeyHex)
    }

    @Test
    fun `an unknown size gives no identity`() {
        assertNull(ManifestSourceIdentity.read(Files(mapOf("f" to big), knownLength = false), Uri.parse("f")))
    }

    @Test
    fun `the manifest is named by the lookup key in hex under the folder`() {
        assertEquals("http://h:8/m/ab.dvmanifest.json", ShotListManifests.manifestUrl("http://h:8/m/", "ab"))
        assertEquals("http://h:8/m/ab.dvmanifest.json", ShotListManifests.manifestUrl("http://h:8/m", "ab"))
    }

    @Test
    fun `the manifest is fetched by the file's lookup key, without the stream's data sources`() {
        val stream = Files(mapOf("http://stream/f.mkv" to big))
        val json = "{\"format\":1}".toByteArray()
        val folder = Files(mapOf("http://oracle/m/9cf1c9b7fcf96ecb9cf75e4dc4a20c66b60f084d044a5a80bf334ef05d5c18ed.dvmanifest.json" to json))
        val manifests = ShotListManifests("http://oracle/m", stream, folder, waitMs = 5_000, executor = Executors.newSingleThreadExecutor())
        val fetched = manifests.forUri(Uri.parse("http://stream/f.mkv")).await()!!
        assertArrayEquals(json, fetched.json)
        assertEquals("9cf1c9b7fcf96ecb9cf75e4dc4a20c66b60f084d044a5a80bf334ef05d5c18ed", fetched.identity.lookupKeyHex)
        assertEquals(2, stream.opens.size)
        assertEquals(1, folder.opens.size)
    }

    @Test
    fun `no manifest in the folder, or no file, gives none`() {
        val stream = Files(mapOf("s" to hello))
        val empty = Files(emptyMap())
        val executor = Executors.newSingleThreadExecutor()
        assertNull(ShotListManifests("http://oracle/m", stream, empty, 5_000, executor).forUri(Uri.parse("s")).await())
        assertNull(ShotListManifests("http://oracle/m", empty, empty, 5_000, executor).forUri(Uri.parse("s")).await())
    }

    @Test
    fun `a manifest that is not there within the wait gives none`() {
        val gate = CountDownLatch(1)
        val stream = object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                gate.await()
                throw IOException("released")
            }
        }
        val pending = ShotListManifests("http://oracle/m", stream, Files(emptyMap()), waitMs = 50, executor = Executors.newSingleThreadExecutor())
            .forUri(Uri.parse("s"))
        assertNull(pending.await())
        gate.countDown()
    }

    @Test
    fun `a stalled fetch for one file does not hold up the next file's fetch`() {
        val gate = CountDownLatch(1)
        val stalled = object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                gate.await()
                throw IOException("released")
            }
        }
        val json = "{}".toByteArray()
        val folder = Files(mapOf("http://oracle/m/9bffb41b7848bdd658c178d4406e85771778622e08e329311931dc5da7747cbe.dvmanifest.json" to json))
        try {
            // Both use the default executor, as every player does.
            assertNull(ShotListManifests("http://oracle/m", stalled, folder, waitMs = 50).forUri(Uri.parse("a")).await())
            val next = ShotListManifests("http://oracle/m", Files(mapOf("b" to hello)), folder, waitMs = 5_000).forUri(Uri.parse("b"))
            assertArrayEquals(json, next.await()!!.json)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `a second extractor for the same file reuses the fetch, another file starts its own`() {
        val manifests = ShotListManifests("http://oracle/m", Files(mapOf("a" to hello, "b" to hello)), Files(emptyMap()), 5_000, Executors.newSingleThreadExecutor())
        val a = manifests.forUri(Uri.parse("a"))
        assertSame(a, manifests.forUri(Uri.parse("a")))
        val b = manifests.forUri(Uri.parse("b"))
        assertEquals(false, a === b)
    }

    @Test
    fun `a manifest longer than the library takes is not downloaded whole`() {
        val tooLong = object : DataSource.Factory {
            override fun createDataSource(): DataSource = object : BaseDataSource(false) {
                override fun open(dataSpec: DataSpec) = DoviRpuBuilder.MAX_MANIFEST_BYTES + 1L
                override fun read(buffer: ByteArray, offset: Int, length: Int) = length
                override fun getUri(): Uri? = null
                override fun close() {}
            }
        }
        assertNull(ShotListManifests.download(tooLong, Uri.parse("m")))
    }
}
