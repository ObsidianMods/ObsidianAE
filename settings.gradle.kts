pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // apksig-android (MuntashirAkon fork) publishes via JitPack only.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Obsidian AE"
include(":app")
include(":apktools")
include(":zipalign")
