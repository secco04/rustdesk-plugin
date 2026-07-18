import java.io.FileInputStream
import java.util.Properties

plugins {
    // AGP 9.x has built-in Kotlin support — the org.jetbrains.kotlin.android plugin must NOT be
    // applied (it errors out). Same as linux-plugin's and remote-desktop-plugin's app/build.gradle.kts.
    id("com.android.application")
}

// Release signing: reads from app/keystore.properties, which is gitignored and never generated
// by this build script — create it yourself (locally only) pointing at your own release keystore:
//   storeFile=/absolute/or/relative/path/to/your.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Missing file = release builds stay unsigned rather than failing the whole build — same
// convention as remote-desktop-plugin's/linux-plugin's app/build.gradle.kts.
val keystorePropertiesFile = file("keystore.properties")
val hasKeystoreConfig = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreConfig) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "de.lobianco.saftssh.rustdesk"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.lobianco.saftssh.rustdesk"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.2"
    }

    buildFeatures {
        aidl = true
    }

    // The Rust core (rust/rustdesk-upstream vendored + rust/android-shim) is built separately via
    // `cargo ndk -t arm64-v8a build` (see plans/soft-frolicking-thimble.md for the full toolchain
    // needed — vcpkg, NDK, Perl, Flutter SDK). Its output .so isn't wired into this Gradle build
    // yet (no rust-android-gradle-plugin task); until that's set up, copy
    // rust/android-shim/target/aarch64-linux-android/debug/libandroid_shim.so into
    // app/src/main/jniLibs/arm64-v8a/ by hand before building this module.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasKeystoreConfig) {
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
            if (hasKeystoreConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

// AGP 9.x / Kotlin 2.x: set the JVM target via the Kotlin compilerOptions DSL (kotlinOptions removed).
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
