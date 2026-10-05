package com.iptv.player.util

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * libVLC validates TLS natively (GnuTLS) against the Android system store, so
 * neither the network security config nor a Java trust manager reaches it.
 * This proves on a real device image that the extra trust directory passed by
 * [BundledRootTrust.vlcOptionsFor] is honoured: an HTTPS server whose root the
 * system does not know is only reached when that root is in the directory.
 */
@RunWith(AndroidJUnit4::class)
class LibVlcTrustDirectoryTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val server = MockWebServer()

    @Before
    fun startServer() {
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .signedBy(root)
            .build()
        server.useHttps(
            HandshakeCertificates.Builder()
                .heldCertificate(serverCertificate, root.certificate)
                .build()
                .sslSocketFactory(),
            false,
        )
        repeat(8) { server.enqueue(MockResponse().setResponseCode(404)) }
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    /**
     * Emulators are x86, where libVLC 3.7.6 crashes in nettle_memxor as soon as
     * it encrypts an AES-GCM record (SIGSEGV right after a successful
     * handshake). Real TV devices are ARM. Steer the x86 run to a suite that
     * does not hit that code path; the trust decision is unaffected.
     */
    private val x86Workaround: List<String> =
        if (Build.SUPPORTED_ABIS.firstOrNull().orEmpty().startsWith("x86")) {
            listOf("--gnutls-priorities=" + System.getProperty("kululu.test.gnutls", GNUTLS_NO_GCM))
        } else {
            emptyList()
        }

    private fun requestReachesServer(extraOptions: List<String>, waitSeconds: Long): Boolean {
        val options = ArrayList(listOf("--no-audio", "--no-video", "--verbose=0") + x86Workaround + extraOptions)
        val vlc = LibVLC(app, options)
        val player = MediaPlayer(vlc)
        try {
            val media = Media(vlc, Uri.parse("https://localhost:${server.port}/live/test.ts"))
            player.media = media
            media.release()
            player.play()
            return server.takeRequest(waitSeconds, TimeUnit.SECONDS) != null
        } finally {
            runCatching { player.stop() }
            player.release()
            vlc.release()
        }
    }

    @Test
    fun withoutTheTrustDirectoryAnUnknownRootIsRejected() {
        assertTrue("no request must arrive", !requestReachesServer(emptyList(), waitSeconds = 8))
    }

    @Test
    fun withTheTrustDirectoryTheSameServerIsReached() {
        val directory = File(app.cacheDir, "vlc-trust-test").apply {
            deleteRecursively()
            check(mkdirs())
        }
        File(directory, "test_root.pem").writeText(root.certificatePem())
        assertTrue(
            "libVLC did not complete an HTTPS request although its root is in the trust directory",
            requestReachesServer(BundledRootTrust.vlcOptionsFor(directory), waitSeconds = 25),
        )
    }

    private companion object {
        const val GNUTLS_NO_GCM = "NORMAL:-AES-128-GCM:-AES-256-GCM"
    }

    @Test
    fun theBundledRootIsWrittenAsTheOnlyFileOfItsDirectory() {
        val directory = BundledRootTrust.writeVlcTrustDirectory(app)
        val files = directory.listFiles().orEmpty()
        assertNotNull(files.singleOrNull())
        assertTrue(files.single().readText().startsWith("-----BEGIN CERTIFICATE-----"))
        assertNull(File(app.noBackupFilesDir, "isrg_root_x1.pem.tmp").takeIf { it.exists() })
    }
}
