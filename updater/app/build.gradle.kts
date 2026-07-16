plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jim.updater"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jim.updater"
        minSdk = 28
        targetSdk = 35
        versionCode = 12
        versionName = "0.0.11"

        // Which app cohort this build exposes. Default "all" = dev/superuser build that
        // sees every app in apps.json. Build a tailored distribution with e.g.
        //   ./gradlew assembleRelease -Pcohort=beta
        // to ship an Updater that lists only apps tagged with that cohort in apps.json.
        val cohort = (project.findProperty("cohort") as String?) ?: "all"
        buildConfigField("String", "COHORT", "\"$cohort\"")
    }

    buildTypes {
        release {
            // Signing with the debug key so a plain `./gradlew assembleRelease` is installable
            // without extra keystore setup, same convention as Tutor/Didact in this workspace.
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
