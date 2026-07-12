import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.gms.google.services) apply false
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.melodie.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.melodie.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Token Discogs optionnel pour augmenter le quota (60 req/min au lieu de 25).
        // A placer dans local.properties : discogs.token=XXXXXXXXXXXXX
        val localProps = Properties()
        val lpFile = rootProject.file("local.properties")
        if (lpFile.exists()) {
            lpFile.inputStream().use { stream -> localProps.load(stream) }
        }
        val discogsToken = localProps.getProperty("discogs.token", "")
        buildConfigField("String", "DISCOGS_TOKEN", "\"$discogsToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

dependencies {
    // AndroidX core / UI
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.fragment)
    implementation(libs.viewpager2)
    implementation(libs.coordinatorlayout)
    implementation(libs.swiperefresh)
    implementation(libs.preference)
    implementation(libs.core.splashscreen)
    implementation(libs.palette)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime)

    // Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    annotationProcessor(libs.room.compiler)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)

    // Hilt
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(libs.hilt.navigation.fragment)
    implementation(libs.hilt.work)
    annotationProcessor(libs.hilt.ext.compiler)

    // WorkManager
    implementation(libs.workmanager)

    // Glide
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)

    // Google Drive
    implementation(libs.play.services.auth)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive)
    implementation(libs.documentfile)
    implementation("com.google.android.gms:play-services-ads:23.3.0")

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

