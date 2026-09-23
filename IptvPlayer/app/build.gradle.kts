// App module build script.
// Targets Android 6+ TV devices and sticks.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

fun buildConfigString(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

// Local diagnostic APKs are opt-in; normal builds keep the same version/policy.
val livePlaybackDiagnostics = System.getenv("LIVE_PLAYBACK_DIAGNOSTICS") == "1"
val tsOnlyTestBuild = providers.gradleProperty("tsOnlyTestBuild").orNull == "true"
val compatibilityTestBuild = providers.gradleProperty("compatibilityTestBuild").orNull == "true"

// Media3 FFmpeg audio decoder extension. Google does not publish it to Maven;
// CI builds it from source (scripts/ffmpeg/build_media3_ffmpeg.sh) and drops
// the AAR here before assembling. When the AAR is present the `ffmpeg` source
// set wires FfmpegAudioRenderer in; otherwise the `noffmpeg` stub keeps every
// existing libVLC fallback path unchanged. See docs/ffmpeg-audio.md.
val ffmpegDecoderAar = file("libs/media3-decoder-ffmpeg-release.aar")
val ffmpegAudio = ffmpegDecoderAar.isFile
val ffmpegSourceSet = if (ffmpegAudio) "src/ffmpeg/java" else "src/noffmpeg/java"
logger.lifecycle(
    "FFmpeg audio extension: " +
        if (ffmpegAudio) "present (${ffmpegDecoderAar.name})" else "absent, using $ffmpegSourceSet stub",
)

// Remote playback-policy signing key (PEM, public half only). Read at
// configuration time so BuildConfig carries it; a missing file yields "" and
// the verifier treats signed policy as unavailable. Line endings are
// normalised so a CRLF checkout produces the same constant as CI.
val policyPublicKeyPem: String =
    file("policy_public_key.pem").takeIf { it.isFile }
        ?.readText(Charsets.UTF_8)
        ?.replace("\r\n", "\n")
        ?.replace("\r", "\n")
        ?.trim()
        ?.let { it + "\n" }
        .orEmpty()

ksp {
    // Room schema history lives in git so migrations can be diffed and tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.iptv.player"
    // Media3 1.11.x and LibVLC 3.7.x require Android 16 build APIs. compileSdk affects
    // build-time symbols; targetSdk deliberately remains 34 in this reliability
    // patch so playback behavior does not change for unrelated platform reasons.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iptv.player"
        minSdk = 23
        targetSdk = 34
        versionCode = 141
        versionName = "1.5.97"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        buildConfigField("boolean", "TS_ONLY_TEST_BUILD", tsOnlyTestBuild.toString())
        if (livePlaybackDiagnostics) versionNameSuffix = "-diag1"
        buildConfigField("boolean", "LIVE_PLAYBACK_DIAGNOSTICS", livePlaybackDiagnostics.toString())
        // True only when the locally built Media3 FFmpeg AAR was on disk at
        // configuration time; production CI always ships it.
        buildConfigField("boolean", "FFMPEG_AUDIO", ffmpegAudio.toString())
        // Public key (PEM) that signs remote playback policy; "" when absent.
        buildConfigField("String", "POLICY_PUBLIC_KEY_PEM", buildConfigString(policyPublicKeyPem))

        // Service credentials are injected by CI/local environment and never
        // committed. Blank values disable the optional integration gracefully.
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            buildConfigString(System.getenv("TMDB_API_KEY").orEmpty()),
        )
        buildConfigField(
            "String",
            "TELEMETRY_INGEST_KEY",
            buildConfigString(System.getenv("TELEMETRY_INGEST_KEY").orEmpty()),
        )

        // Limit native ABIs to keep APK small and cover common TV/stick chipsets.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    signingConfigs {
        // Release signing is driven entirely by environment variables so the
        // keystore file and its passwords never live in the repository. CI decodes
        // a base64 keystore secret and exports these vars. When they are absent
        // (local debug builds, or a fork without secrets) the release stays
        // unsigned and the signingConfig below is simply not applied.
        create("release") {
            val storePath = System.getenv("KEYSTORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Apply the stable release key only when a keystore was provided via
            // the environment; otherwise Gradle would fail signing with an empty
            // config and the unsigned APK still builds for inspection.
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            if (tsOnlyTestBuild) {
                applicationIdSuffix = ".preview"
                versionNameSuffix = "-preview1"
                manifestPlaceholders["appLabel"] = "Kululu IPTV Preview"
            } else if (compatibilityTestBuild) {
                applicationIdSuffix = ".compat"
                versionNameSuffix = "-compat-test"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // libVLC ships large native libs; avoid recompressing them.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        // Exactly one of the two FfmpegAudio implementations is compiled in.
        getByName("main") {
            java.srcDir(ffmpegSourceSet)
        }
    }

    testOptions {
        unitTests {
            // Let unit tests hit android.jar stubs without "Method not mocked"
            // crashes; our player tests inject fakes and never touch real
            // framework behavior, so default-valued stubs are sufficient.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Kotlin / Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- AndroidX core / lifecycle ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // --- Android TV (Leanback) ---
    implementation("androidx.leanback:leanback:1.0.0")

    // --- Persistence (Room) ---
    // Room's compiler must support Kotlin 2.2 / KSP2 used by Media3's toolchain.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    // Room PagingSource support (Room -> Paging 3 bridge).
    implementation("androidx.room:room-paging:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // --- Paging 3 (bounded, lazily-loaded lists for huge catalogs) ---
    implementation("androidx.paging:paging-runtime-ktx:3.3.2")

    // --- DataStore (settings / saved login) ---
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Background sync (auto-refresh playlist + EPG) ---
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- Chromecast (sender; optional, guarded by Play-services availability) ---
    implementation("androidx.mediarouter:mediarouter:1.6.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")

    // --- Networking (Retrofit + OkHttp + Gson) ---
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // --- Image loading (Coil, with downscaling/caching for weak devices) ---
    implementation("io.coil-kt:coil:2.6.0")

    // --- Players ---
    // Media3 ExoPlayer = primary engine.
    // Keep the FFmpeg extension pin in scripts/ffmpeg/versions.env in sync.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    // FFmpeg software audio decoders (MP2/MP3/AAC/AC-3/E-AC-3/DTS/Opus/Vorbis/
    // FLAC/ALAC/MLP/TrueHD) built from the same media3 tag by CI. Only bundled
    // when the AAR exists; the build stays green without it (libVLC covers audio).
    if (ffmpegAudio) {
        implementation(files(ffmpegDecoderAar))
    }
    // Audio-only MPEG Layer I/II/III fallback. No second player/network pull and
    // no native video decoder: Media3 retains the device's hardware video path.
    implementation("javazoom:jlayer:1.0.1")
    // libVLC = fallback engine (broadest codec coverage: DTS/AC3/EAC3/etc.).
    // Use the stable 3.x Android SDK; 4.x EAP artifacts are preview builds.
    implementation("org.videolan.android:libvlc-all:3.7.6")
    // In-app YouTube trailer playback (WebView IFrame player; no Play-services /
    // YouTube app required, works on plain Android TV boxes).
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0")

    // --- Unit tests (pure JVM; no emulator / Robolectric) ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // Mockito provides no-op stubs for the Context/ViewGroup the controller
    // stores but never really uses in tests (engine + scheduler are faked).
    testImplementation("org.mockito:mockito-core:5.12.0")
}

// Keep direct local assemble invocations honest too: a failing policy/redaction
// test fails the APK build even outside the CI workflow.
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}
