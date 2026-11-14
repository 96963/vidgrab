plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.io.File
import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "com.vidgrab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vidgrab.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "1.4.3"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // 签名配置：从 keystore.properties 读取（不提交到版本控制）
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    val hasKeystore = keystorePropertiesFile.exists()
    if (hasKeystore) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    val ksStoreFile = keystoreProperties.getProperty("storeFile") ?: ""
    val ksStorePassword = keystoreProperties.getProperty("storePassword") ?: ""
    val ksKeyAlias = keystoreProperties.getProperty("keyAlias") ?: ""
    val ksKeyPassword = keystoreProperties.getProperty("keyPassword") ?: ""

    if (hasKeystore && ksStoreFile.isNotBlank() && ksStorePassword.isNotBlank() && ksKeyAlias.isNotBlank() && ksKeyPassword.isNotBlank()) {
        signingConfigs {
            create("release") {
                storeFile = File(ksStoreFile)
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore && ksStoreFile.isNotBlank() && ksStorePassword.isNotBlank() && ksKeyAlias.isNotBlank() && ksKeyPassword.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // youtubedl-android 运行时依赖
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")

    debugImplementation("androidx.compose.ui:ui-tooling")
}