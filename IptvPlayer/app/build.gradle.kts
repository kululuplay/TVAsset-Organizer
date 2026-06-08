// App module build script.
// Targets Android TV + sticks: minSdk 21 for broad coverage, modern targetSdk.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.iptv.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iptv.player"
        minSdk = 21
        targetSdk = 34
        versionCode = 53
        versionName = "1.5.9"

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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    testOptions {
        unitTests {
            // Let unit tests hit android.jar stubs without "Method not mocked"
            // crashes; our player tests inject fakes and never touch real
            // framework behavior, so default-valued stubs are sufficient.
            isReturnDefaultValues = true
        }
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
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    // Room PagingSource support (Room -> Paging 3 bridge).
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

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
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // libVLC = fallback engine (broadest codec coverage: DTS/AC3/EAC3/etc.).
    implementation("org.videolan.android:libvlc-all:3.5.1")
    // In-app YouTube trailer playback (WebView IFrame player; no Play-services /
    // YouTube app required, works on plain Android TV boxes).
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0")

    // --- Unit tests (pure JVM; no emulator / Robolectric) ---
    testImplementation("junit:junit:4.13.2")
    // Mockito provides no-op stubs for the Context/ViewGroup the controller
    // stores but never really uses in tests (engine + scheduler are faked).
    testImplementation("org.mockito:mockito-core:5.12.0")
}
