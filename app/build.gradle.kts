import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Legge ORS_API_KEY da local.properties (gitignored) o variabile d'ambiente
val orsKey: String = run {
    val f = rootProject.file("local.properties")
    val props = Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
    props.getProperty("ORS_API_KEY") ?: System.getenv("ORS_API_KEY") ?: ""
}

android {
    namespace = "it.stradasafe.liguria"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.stradasafe.liguria"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.0"
        buildConfigField("String", "ORS_API_KEY", "\"$orsKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.maplibre.gl:android-sdk:11.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
