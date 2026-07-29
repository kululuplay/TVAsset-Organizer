// Root build file. Plugin versions are declared here and applied per-module.
plugins {
    // AGP 8.6.1 is the first compatible patch line that officially supports
    // compileSdk 35 while retaining the existing Gradle 8.7 / JDK 17 toolchain.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
