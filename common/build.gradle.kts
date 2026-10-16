plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.work:work-runtime:2.12.0")
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
    // The bridge snapshot parser also runs as a plain JVM test.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}

// ZIP source updates cannot delete old files automatically. Keep the retired injector out of APKs.
val verifyLegacyFullscreenRemoved by tasks.registering {
    // Capture only the path; calling file() inside doLast captures the build script.
    val retiredAssets = layout.projectDirectory.dir("src/main/assets/voyah-root").asFile
    doLast {
        check(!retiredAssets.exists()) {
            "Run powershell -NoProfile -ExecutionPolicy Bypass -File scripts/root-module/remove-legacy-sources.ps1 from the project root, then rebuild."
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyLegacyFullscreenRemoved) }
