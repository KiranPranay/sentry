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
        // Tara Core is built and published locally from /home/pranay/projects/taracore
        // via `./gradlew publishToMavenLocal`. It is not on a public repository.
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Sentry"
include(":app")
 