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
        maven {
            url = uri("https://maven.pkg.github.com/FastPix/android-core-data-sdk")
            credentials {
                username = "github-username"
                password = "github-token"
            }
        }
    }
}

rootProject.name = "ExoPlayerData"
include(":app")
include(":exoplayer-data-sdk")
