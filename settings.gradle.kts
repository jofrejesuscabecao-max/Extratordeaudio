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
        // OBRIGATÓRIO: O servidor onde a biblioteca está hospedada
        maven { url = uri("https://jitpack.io") } 
    }
}

rootProject.name = "AudioExtractor"
include(":app")


