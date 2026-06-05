// Top-level settings: declares plugin/dependency repositories and modules.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // VideoLAN repo hosts libVLC artifacts.
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "IptvPlayer"
include(":app")
