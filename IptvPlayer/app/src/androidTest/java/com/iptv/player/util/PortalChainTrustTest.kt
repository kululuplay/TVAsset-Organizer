package com.iptv.player.util

import android.net.http.X509TrustManagerExtensions
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate trust as the app really has it on this Android version, checked
 * against the chain kululu.live served on 5 Oct 2026 (assets/tls). Run it on
 * Android 6.0 and 7.0 emulators: their system stores lack ISRG Root X1, which
 * is what made every login fail with NET-TLS on a 2015 Sony BRAVIA.
 * No network is involved, so a TLS-intercepting antivirus cannot distort it.
 */
@RunWith(AndroidJUnit4::class)
class PortalChainTrustTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val chain: Array<X509Certificate> by lazy {
        instrumentation.context.assets.open("tls/kululu_live_chain.pem").use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input)
                .map { it as X509Certificate }
                .toTypedArray()
        }
    }

    private fun assumeFixtureStillValid() =
        assumeTrue("refresh assets/tls/kululu_live_chain.pem", chain.first().notAfter.after(Date()))

    private fun platformTrustManager(anchors: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /** The trust OkHttp, Media3 and Coil get inside this app. */
    @Test
    fun appTrustAcceptsThePortalChain() {
        assumeFixtureStillValid()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Network security config: host-aware check, as the TLS stack does it.
            X509TrustManagerExtensions(platformTrustManager(null))
                .checkServerTrusted(chain, "ECDHE_ECDSA", "kululu.live")
        } else {
            BundledRootTrust.trustManager(instrumentation.targetContext)
                .checkServerTrusted(chain, "ECDHE_ECDSA")
        }
    }

    /** The defect itself: before Android 7.1.1 the system store cannot anchor the chain. */
    @Test
    fun systemStoreAloneRejectsThePortalChainOnAndroid6And7() {
        assumeFixtureStillValid()
        assumeTrue(Build.VERSION.SDK_INT <= Build.VERSION_CODES.N)
        val systemStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        try {
            platformTrustManager(systemStore).checkServerTrusted(chain, "ECDHE_ECDSA")
            fail("this image unexpectedly trusts ISRG Root X1")
        } catch (expected: CertificateException) {
            // Trust anchor for certification path not found.
        }
    }

    /** The bundled root must not make the app accept anything else. */
    @Test
    fun aServerWithAnUnknownRootIsStillRejected() {
        val unknownRoot = HeldCertificate.Builder().certificateAuthority(0).build()
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .signedBy(unknownRoot)
            .build()
        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder()
                .heldCertificate(serverCertificate, unknownRoot.certificate)
                .build()
                .sslSocketFactory(),
            false,
        )
        server.enqueue(MockResponse().setBody("must not be delivered"))
        server.start()
        try {
            BundledRootTrust.install(instrumentation.targetContext)
            val client = OkHttpClient.Builder().trustBundledRootOnLegacyAndroid().build()
            val url = server.url("/").newBuilder().host("localhost").build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
                fail("a certificate from an unknown root was accepted")
            } catch (expected: SSLException) {
                // Rejected, as it must be.
            }
        } finally {
            server.shutdown()
        }
    }
}
