import java.util.Properties

// The watch app: shows what's playing on the phone, with play/pause and ±15 s. It must share the
// phone app's package name and signing key, which the Wear OS Data Layer needs to pair them.
val appVersionName: String = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("versionName")
val appVersionCode: Int = appVersionName.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

val keystoreProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun signingValue(key: String, env: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)
val releaseStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leatherbound.listening.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leatherbound.listening"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.icons.extended)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
}
