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
        versionCode = 4
        versionName = "1.3" // Versão com Python 3.10+

        // Mantemos o filtro para garantir compatibilidade com seu Moto G75
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64")
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
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- A GRANDE MUDANÇA ---
    // Removemos a biblioteca antiga (yausername) que tinha Python 3.8
    // Adicionamos a biblioteca moderna (JunkFood02) que tem Python 3.10+
    // Isso resolve o erro "Unsupported version of Python"
    implementation("io.github.junkfood02:youtubedl-android:0.17.3")
    implementation("io.github.junkfood02:youtubedl-android:ffmpeg:0.17.3") 
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}


