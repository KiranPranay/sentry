plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sentry"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.sentry"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        compose = true
    }

    // The Vosk acoustic model is mmap'd straight out of the APK-adjacent copy, and
    // compressing it would only cost us time on first unpack.
    androidResources {
        noCompress += listOf("task", "tflite", "bin", "ort")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/INDEX.LIST",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    // On-device inference. Brings dev.taracore:api (the AIDL contract) with it.
    implementation(libs.taracore.client.sdk)

    // Gemini Nano through AICore. Experimental and device-gated: every call site is
    // behind a runtime availability check, and Tara Core is the fallback.
    implementation(libs.aicore)

    // Offline speech recognition, used for both the hotword and full dictation.
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.vosk.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
