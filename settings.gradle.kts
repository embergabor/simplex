pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// Optional, in local.properties (not committed):
//   simplexCore.dir=../simplex-core     builds the core from that checkout instead of the published version,
//                                       to work on both at once
//   simplexMaven.dir=../simplex-maven   takes published versions from that checkout before they're pushed
val local = java.util.Properties().apply {
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        local.getProperty("simplexMaven.dir")?.let { dir -> maven { url = uri(file(dir)) } }
        // simplex-core, the Plex connection and the player, shared with PlexVerse (github.com/embergabor/simplex-core).
        maven("https://raw.githubusercontent.com/embergabor/simplex-maven/main") {
            content { includeGroup("com.embergabor") }
        }
    }
}
rootProject.name = "Simplex"
include(":app")
local.getProperty("simplexCore.dir")?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution { substitute(module("com.embergabor:simplex-core")).using(project(":core")) }
    }
}
include(":baselineprofile")
