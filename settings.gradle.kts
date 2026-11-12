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
    }
}
rootProject.name = "Simplex"
include(":app")
// The Plex connection and the player, shared with PlexVerse; published as com.embergabor:simplex-core.
include(":core")
include(":baselineprofile")
