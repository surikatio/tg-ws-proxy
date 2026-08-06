// Explicit import: inside a Gradle Kotlin script `java` resolves to the Java
// plugin extension, so `java.util.Properties` does not compile.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Signing details live in keystore.properties, which is never committed:
// storeFile, storePassword, keyAlias, keyPassword. Without it the release
// build still works, it just falls back to the debug signature.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "org.tgwsproxy.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.tgwsproxy.android"
        // 26, not lower: the service relies on startForegroundService() and
        // notification channels, both introduced in Android 8.0. Declaring 24
        // would let it install on Android 7 and then crash on start.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Chaquopy ships a Python runtime per ABI; these two cover every
            // phone still in use. Dropping the 32-bit ones halves the APK.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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

        // No pip dependencies on purpose. There is no cryptography wheel for
        // Android, and building it needs a Rust toolchain; proxy/_aes.py
        // instead falls back to javax.crypto, which every device ships.
        // This also keeps the build working without reaching pypi.org.
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

// Chaquopy's merge task consumes that directory, so it has to wait for the
// copy; depending on preBuild alone leaves the order undefined.
tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("PythonSources")
}.configureEach {
    dependsOn(syncProxyCore)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
