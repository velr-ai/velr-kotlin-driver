pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "velr-kotlin-driver"

val includeAndroid = providers.gradleProperty("includeAndroid").orNull.equals("true", ignoreCase = true) ||
    providers.environmentVariable("VELR_INCLUDE_ANDROID").orNull.equals("true", ignoreCase = true)

if (includeAndroid) {
    include(":android")
}
