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
        versionCode = 9
        versionName = "0.0.8"
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// Regenerate the <queries> package-visibility list in AndroidManifest.xml from
// ../apps.json so it can never drift from the app roster. apps.json is fetched at
// runtime, but Android 11+ package visibility is fixed at build time, so a package
// missing here makes getPackageInfo() throw and the app render as "not installed"
// even when it is. Runs before every build; new apps become visible on the next
// Updater build. See the GENERATED:queries markers in AndroidManifest.xml.
val syncQueries by tasks.registering {
    description = "Regenerate the <queries> package list in AndroidManifest.xml from apps.json"
    val appsJson = rootProject.projectDir.resolve("../apps.json")
    val manifest = projectDir.resolve("src/main/AndroidManifest.xml")
    inputs.files(appsJson)
    outputs.file(manifest)
    doLast {
        if (!appsJson.exists()) {
            logger.warn("syncQueries: ${appsJson} not found — leaving <queries> as-is")
            return@doLast
        }
        val packages = Regex("\"packageName\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(appsJson.readText())
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        require(packages.isNotEmpty()) { "no packageName entries found in ${appsJson}" }

        val indent = "        "
        val generated = buildString {
            append("<!-- GENERATED:queries:BEGIN (from apps.json — do not edit by hand) -->")
            packages.forEach { append("\n$indent<package android:name=\"$it\" />") }
            append("\n$indent<!-- GENERATED:queries:END -->")
        }
        val blockRegex = Regex(
            "<!-- GENERATED:queries:BEGIN.*?GENERATED:queries:END -->",
            RegexOption.DOT_MATCHES_ALL,
        )
        val text = manifest.readText()
        require(blockRegex.containsMatchIn(text)) {
            "GENERATED:queries markers not found in ${manifest}"
        }
        val updated = text.replace(blockRegex, generated)
        if (updated != text) {
            manifest.writeText(updated)
            logger.lifecycle("syncQueries: wrote ${packages.size} package(s) to AndroidManifest.xml")
        }
    }
}

tasks.named("preBuild") { dependsOn(syncQueries) }
