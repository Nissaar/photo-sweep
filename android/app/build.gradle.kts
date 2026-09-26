import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Written out rather than derived from the release tag, so anyone building from a
 * checkout gets the version the release did. F-Droid builds from the tag with no
 * RELEASE_TAG in its environment, and a derived version came out as 0.1.0-dev.
 *
 * The release workflow checks that the tag agrees with versionName and that a
 * changelog exists for versionCode, so the two cannot drift apart unnoticed.
 *
 * Android decides what counts as an update purely from versionCode, and it must
 * increase every release or the new APK will not install over the old one. The
 * scheme is major * 10000 + minor * 100 + patch.
 */
val appVersionName = "1.0.2"
val appVersionCode = 10002

android {
    namespace = "io.github.nissaar.photosweep"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nissaar.photosweep"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // F-Droid's scanner flags the dependency list AGP embeds in the APK, which is
    // encrypted for Google alone, and that blob also stops a rebuilt APK from
    // matching the published one byte for byte.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Kotlin 2.x removed the old kotlinOptions DSL, and 2.4 turned using it into an
// error rather than a warning. Same JVM target, stated the way the plugin now wants.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
