plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val starApiKey = providers.environmentVariable("STAR_AI_API_KEY").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "~".repeat(53)
val starKeyMask = byteArrayOf(
    0x53, 0x74, 0x61, 0x72, 0x41, 0x49, 0x2D, 0x4F, 0x66, 0x66, 0x6C, 0x69, 0x6E, 0x65, 0x21
)
val starKeyCipher = ByteArray(starApiKey.toByteArray(Charsets.UTF_8).size) { index ->
    (starApiKey.toByteArray(Charsets.UTF_8)[index].toInt() xor starKeyMask[index % starKeyMask.size].toInt()).toByte()
}
val starKeyBlob = java.util.Base64.getEncoder().encodeToString(starKeyCipher)

android {
    namespace = "com.cyberpulse.starai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cyberpulse.starAI"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "STAR_AI_KEY_BLOB", "\"$starKeyBlob\"")
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
