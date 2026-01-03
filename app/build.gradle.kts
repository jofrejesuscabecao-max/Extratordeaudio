plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.audioextractor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.audioextractor"
        minSdk = 24 
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        // FORÇAR ARM64: Isso garante que o binário certo vá para o seu Moto G75
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64") // Para emuladores se precisar
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

    buildFeatures {
        viewBinding = true
    }
    
    // Evita conflitos de arquivos nativos
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Versão 0.15.0 (A mais estável atualmente)
    implementation("com.github.yausername.youtubedl-android:library:0.15.0")
    implementation("com.github.yausername.youtubedl-android:ffmpeg:0.15.0") // FFmpeg é crucial
    implementation("com.github.yausername.youtubedl-android:aria2c:0.15.0") // Aria2c ajuda em downloads rápidos
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}


