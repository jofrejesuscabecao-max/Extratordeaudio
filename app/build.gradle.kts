// Arquivo: app/build.gradle.kts
// Configurações do módulo do app e dependências do yt-dlp

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.audioextractor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.audioextractor"
        minSdk = 24 // Android 7.0 (Necessário para algumas libs modernas)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Importante: Força o uso da arquitetura mais comum de celulares modernos
        // para evitar erros de split/abi no emulador ou build.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Habilita ViewBinding para facilitar o código da UI
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- O CORAÇÃO DO EXTRATOR ---
    // Biblioteca wrapper do yt-dlp
    implementation("com.github.ya-username.youtubedl-android:library:0.16.0")
    // Biblioteca FFmpeg (essencial para converter/extrair áudio)
    implementation("com.github.ya-username.youtubedl-android:ffmpeg:0.16.0")
    
    // Coroutines para não travar a UI
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

