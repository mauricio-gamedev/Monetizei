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

rootProject.name = "Monetizei"
include(":protocol")
include(":server")
if (System.getenv("MONETIZEI_SERVER_ONLY") != "true") {
    include(":app")
}
