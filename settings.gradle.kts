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

rootProject.name = "Modrith"
include(
    ":app",
    ":core",
    ":downloader",
    ":filesystem",
    ":installer",
    ":launcher",
    ":models",
    ":orchestrator",
    ":parser",
    ":resolver",
    ":ui",
    ":utils",
)
