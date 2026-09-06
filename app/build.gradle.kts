plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Obfuscated client credential blobs. Plaintext credentials are not exposed
// to the HTML/UI and are reconstructed only inside the native request layer.
val starKeyBlob = listOf(
    "EiVPMyNxfwFQKhocMV1s",
    "YwVMCCoERB8nBQMRKiti",
    "GkFMNggKSX0WSwAqDA5M",
    "ChkDQTR7FTg="
).joinToString("")

val starRouteBKeyBlob = listOf(
    "Ix5BHBd/GURZV3BcVlBX",
    "WhJmE1pLBGNYERVULlEA",
    "XFAPGWJMXhUBMAlCEAdw",
    "UFNUUA9CMRAKF11mW0UQ",
    "XXFRVVVcDxVmQQoWUA=="
).joinToString("")
val starRouteBUrlBlob = listOf(
    "OAEYAxZoQFobFS0HFgsQ",
    "GkQiWw0aSjMfHFsTeUYH",
    "DAQaDjMaAQMJNxscGws7"
).joinToString("")
val starRouteBModelBlob = "PwUJHRc9GgERF2cIERAK"

android {
    namespace = "com.cyberpulse.starai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cyberpulse.starAI"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "STAR_AI_KEY_BLOB", "\"$starKeyBlob\"")
        buildConfigField("String", "STAR_ROUTE_B_KEY_BLOB", "\"$starRouteBKeyBlob\"")
        buildConfigField("String", "STAR_ROUTE_B_URL_BLOB", "\"$starRouteBUrlBlob\"")
        buildConfigField("String", "STAR_ROUTE_B_MODEL_BLOB", "\"$starRouteBModelBlob\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Wire the secondary native route into the existing bridge at build time.
// This keeps the checked-in UI and HTML free of provider-specific details.
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val source = file("src/main/java/com/cyberpulse/starai/MainActivity.kt")
        if (source.exists()) {
            val original = source.readText()
            val needle = "runCatching { callGemini(payloadJson) }\n                    .onSuccess"
            if (original.contains(needle) && !original.contains("StarRouteFallback.call(payloadJson)")) {
                source.writeText(
                    original.replace(
                        needle,
                        "runCatching { callGemini(payloadJson) }\n                    .recoverCatching { StarRouteFallback.call(payloadJson) }\n                    .onSuccess"
                    )
                )
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-analytics")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
