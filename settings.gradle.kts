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
        // OBRIGATÓRIO para baixar a lib do YoutubeDL
        maven { url = uri("https://jitpack.io") } 
    }
}

rootProject.name = "AudioExtractor"
include(":app")


