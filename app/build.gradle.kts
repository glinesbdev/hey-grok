import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String) = (localProps.getProperty(key) ?: System.getenv(key) ?: "").replace("\"", "")

android {
    namespace = "dev.glinesb.heygrok"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.glinesb.heygrok"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"${secret("PICOVOICE_ACCESS_KEY")}\"")
        buildConfigField("String", "XAI_API_KEY", "\"${secret("XAI_API_KEY")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("ai.picovoice:porcupine-android:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
