pluginManagement {
    repositories {
        // dl.google.com is the reachable mirror of Google's Maven repository in some regions
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        google()
    }
}

rootProject.name = "ComicViewer"
include(":app")
