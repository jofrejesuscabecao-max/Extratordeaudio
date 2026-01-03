// Arquivo: build.gradle.kts (Raiz do Projeto)
// Este arquivo configura os plugins globais

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Versão segura do plugin Android para compatibilidade
        classpath("com.android.tools.build:gradle:8.2.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    }
}

plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}

