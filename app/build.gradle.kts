import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Server URL / API key are environment-specific secrets, so they live in the gitignored
// local.properties instead of build.gradle.kts. See README.md for how to set them up.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val spamShieldServerUrl: String =
    localProperties.getProperty("SERVER_BASE_URL") ?: "http://10.0.2.2:3000"
val spamShieldApiKey: String =
    localProperties.getProperty("TELEMETRY_API_KEY") ?: "dev-local-key"

android {
    namespace = "com.spamshield.app"
    compileSdk = 36 // Updated to meet AndroidX requirements

    androidResources {
        noCompress += "tflite"
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.spamshield.app"
        minSdk = 24
        targetSdk = 36 // Updated to match compileSdk
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SERVER_BASE_URL", "\"$spamShieldServerUrl\"")
        buildConfigField("String", "TELEMETRY_API_KEY", "\"$spamShieldApiKey\"")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litert:litert:2.2.0")
}