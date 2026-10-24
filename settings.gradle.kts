pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "BolbolTV"

// Modules are added as their tasks start (see docs/plan.md). New module recipe: docs/modules.md
include(":app")
include(":core:designsystem")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:player")
include(":feature:home")
include(":feature:details")
include(":feature:playback")
include(":feature:search")
include(":feature:browse")
include(":feature:settings")
include(":feature:tvhome")
include(":core:lanremote")
include(":feature:account")
include(":core:download")
include(":core:update")
include(":feature:downloads")
include(":baselineprofile")
include(":benchmark")
