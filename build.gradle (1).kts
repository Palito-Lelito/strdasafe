plugins {
    id("com.android.application")
}

android {
    namespace = "it.lucarisso.rotta"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.lucarisso.rotta"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // firmato con la chiave di debug: basta per installarlo sul proprio telefono
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
