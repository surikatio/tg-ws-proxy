plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "org.tgwsproxy.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.tgwsproxy.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Chaquopy ships a Python runtime per ABI; these two cover every
            // phone still in use. Dropping the 32-bit ones halves the APK.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        // The proxy core is pure Python plus cryptography; _aes.py falls back
        // to other backends, but the wheel is available so use it.
        pip {
            install("cryptography==43.0.3")
        }
    }

    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
            // Populated by syncProxyCore below.
            srcDir(layout.buildDirectory.dir("python-src"))
        }
    }
}

// Reuse the repository's proxy/ package rather than keeping a second copy in
// sync. Only that package is copied — the tray modules import pystray and
// customtkinter, which do not exist on Android.
val syncProxyCore = tasks.register<Copy>("syncProxyCore") {
    description = "Copy the shared proxy/ core into the Python source set"
    from(rootProject.file("../proxy")) {
        exclude("__pycache__/**", "*.pyc")
    }
    into(layout.buildDirectory.dir("python-src/proxy"))
}

tasks.named("preBuild") {
    dependsOn(syncProxyCore)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
