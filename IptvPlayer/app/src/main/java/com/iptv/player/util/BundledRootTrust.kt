package com.iptv.player.util

import android.content.Context
import android.os.Build
import com.iptv.player.R
import okhttp3.OkHttpClient
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ISRG Root X1 for devices whose certificate store predates it.
 *
 * The portal certificate comes from Let's Encrypt and chains to ISRG Root X1,
 * which Android only knows since 7.1.1. A 2015 Sony BRAVIA on Android 7.0
 * therefore failed every login with NET-TLS although its date and time were
 * right (5 Oct 2026). The root ships with the app (res/raw/isrg_root_x1.pem):
 *  - Android 7.0 and later: the network security config adds it for every Java
 *    TLS stack (OkHttp, HttpsURLConnection/Media3, Coil).
 *  - Android 6.0 has no network security config, so OkHttp clients and the
 *    HttpsURLConnection default get [trustManager] from here.
 *  - libVLC validates natively against the system store and is given the PEM
 *    through `--gnutls-dir-trust` ([vlcOptions]).
 * The system store is always asked first; the bundled root only rescues chains
 * the device cannot anchor itself.
 */
internal object BundledRootTrust {

    /** Android 6.0: no network security config, trust has to be wired in code. */
    val needsProgrammaticTrust: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.N

    /**
     * Android 6.0 and 7.0: the store libVLC reads lacks the root. 7.1 is left
     * alone on purpose: 7.1.1+ (Fire OS 6) already has it and works today.
     */
    val nativeStoreLacksRoot: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N

    private const val TAG = "BundledRootTrust"
    private const val VLC_TRUST_DIR = "tls-trust"
    private const val PEM_NAME = "isrg_root_x1.pem"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var material: Pair<X509TrustManager, SSLSocketFactory>? = null

    @Volatile
    private var preparedVlcOptions: List<String> = emptyList()

    /** Call once at process start, before any HTTPS client is built. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (needsProgrammaticTrust) {
            runCatching { HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory(app)) }
                .onFailure { Logger.e(TAG, "bundled root not installed for HttpsURLConnection", it) }
        }
        if (nativeStoreLacksRoot) {
            // Tiny one-off file write; keep it off the main thread.
            Thread({ prepareVlcTrust(app) }, "tls-trust-prepare").start()
        }
    }

    /** System trust first, the bundled root as the fallback anchor. */
    fun trustManager(context: Context): X509TrustManager = material(context).first

    fun socketFactory(context: Context): SSLSocketFactory = material(context).second

    /** Extra libVLC options; empty where the native store already has the root. */
    fun vlcOptions(): List<String> = preparedVlcOptions

    internal fun vlcOptionsFor(trustDirectory: File): List<String> =
        listOf("--gnutls-dir-trust=${trustDirectory.absolutePath}")

    internal fun appContextOrNull(): Context? = appContext

    private fun prepareVlcTrust(app: Context) {
        runCatching {
            val directory = writeVlcTrustDirectory(app)
            preparedVlcOptions = vlcOptionsFor(directory)
        }.onFailure { Logger.e(TAG, "libVLC trust directory not prepared", it) }
    }

    /** A directory holding only the PEM, as gnutls loads every file in it. */
    internal fun writeVlcTrustDirectory(app: Context): File {
        val expected = app.resources.openRawResource(R.raw.isrg_root_x1).use { it.readBytes() }
        val directory = File(app.noBackupFilesDir, VLC_TRUST_DIR)
        val pem = File(directory, PEM_NAME)
        if (!pem.isFile || pem.length() != expected.size.toLong()) {
            check(directory.isDirectory || directory.mkdirs()) { "cannot create $directory" }
            // Written next to the directory, then moved in, so gnutls never
            // sees a partial file.
            val staging = File(app.noBackupFilesDir, "$PEM_NAME.tmp")
            staging.writeBytes(expected)
            pem.delete()
            check(staging.renameTo(pem)) { "cannot move the root into $directory" }
        }
        return directory
    }

    private fun material(context: Context): Pair<X509TrustManager, SSLSocketFactory> =
        material ?: synchronized(this) {
            material ?: build(context.applicationContext).also { material = it }
        }

    private fun build(app: Context): Pair<X509TrustManager, SSLSocketFactory> {
        val system = platformTrustManager(null)
        val root = app.resources.openRawResource(R.raw.isrg_root_x1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("isrg-root-x1", root)
        }
        val bundled = platformTrustManager(anchors)
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (original: CertificateException) {
                    try {
                        bundled.checkServerTrusted(chain, authType)
                    } catch (secondary: CertificateException) {
                        original.addSuppressed(secondary)
                        throw original
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + bundled.acceptedIssuers
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return trust to tls.socketFactory
    }

    private fun platformTrustManager(anchors: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
}

/**
 * Android 6.0 only: give this client the bundled root. Later versions take it
 * from the network security config and keep the platform default untouched.
 * Hostname verification stays OkHttp's own.
 */
internal fun OkHttpClient.Builder.trustBundledRootOnLegacyAndroid(): OkHttpClient.Builder {
    if (!BundledRootTrust.needsProgrammaticTrust) return this
    val context = BundledRootTrust.appContextOrNull() ?: return this
    return runCatching {
        sslSocketFactory(
            BundledRootTrust.socketFactory(context),
            BundledRootTrust.trustManager(context),
        )
    }.getOrElse {
        Logger.e("BundledRootTrust", "bundled root not applied to an HTTP client", it)
        this
    }
}
