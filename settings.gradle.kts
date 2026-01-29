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

rootProject.name = "DawnCourse"
include(":app")
include(":core:data")
include(":core:domain")
include(":core:ui")
include(":feature:timetable")
// include(":feature:import") // Phase 2
// include(":feature:widget") // Phase 3
