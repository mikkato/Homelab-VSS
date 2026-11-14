import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// top10-9: versionName/versionCode из git (tag + число коммитов + sha), без внешних плагинов
fun gitOut(vararg args: String): String = try {
    val out = ByteArrayOutputStream()
    exec { commandLine("git", "-C", rootProject.projectDir.absolutePath, *args); standardOutput = out }
    out.toString().trim()
} catch (_: Exception) { "" }

val gitSha = gitOut("rev-parse", "--short", "HEAD")
val gitCommits = gitOut("rev-list", "--count", "HEAD").toIntOrNull()
val gitTag = gitOut("describe", "--tags", "--abbrev=0").removePrefix("v")
val gitDirty = gitOut("status", "--porcelain").isNotEmpty()

// top10-S2: прод-подпись из keystore.properties (gitignored); отсутствие = debug
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ru.mytolm.frgate"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.mytolm.frgate"
        minSdk = 35
        targetSdk = 36
        // versionCode = число коммитов (монотонно); versionName = база 2.0.0 + git-метка
        versionCode = gitCommits ?: 2
        versionName = "2.0.0+" + listOfNotNull(
            gitTag.ifBlank { null },
            gitSha.takeIf { it.isNotBlank() }?.let { if (gitDirty) "$it-dirty" else it }
        ).joinToString(".").ifBlank { "local" }
    }

    // top10-9: per-ABI APK вместо универсального — libwebrtc весит десятки МБ на ABI
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Прод-подпись: keystore.properties в корне (gitignored); без него — debug
            signingConfig = if (keystoreProps.isNotEmpty())
                signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    lint {
        // known issue: детектор androidx.lifecycle NullSafeMutableLiveData падает
        // с IncompatibleClassChangeError (конфликт версий lint/lifecycle 2.9.x)
        disable += "NullSafeMutableLiveData"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    // coil-network-okhttp не отдаёт классы на android-класспас (KMP metadata bug 3.0.4) — свой AuthHttpFetcher

    implementation(libs.security.crypto)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)

    // Лёгкий WebRTC-клиент для go2rtc (экспериментальный live-режим, §3.2)
    implementation(libs.webrtc)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.ui.tooling)
}
