// Root build file. Plugin versions are declared here and applied per-module.
plugins {
    // Android 16 build APIs and Media3's Kotlin 2.2 metadata; retain JDK 17.
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
