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
        // JitPack fica aqui como backup, mas a biblioteca principal virá do MavenCentral
        maven { url = uri("https://jitpack.io") } 
    }
}

rootProject.name = "AudioExtractor"
include(":app")


