import java.net.URL
import java.util.Properties

// The version lives in version.properties (scripts/release.sh bumps it). versionCode is derived,
// so 1.2.3 -> 10203 and every release installs over the last.
val appVersionName: String = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("versionName")
val appVersionCode: Int = appVersionName.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Release signing: environment variables (GitHub Actions) or keystore.properties (this PC, not committed).
val keystoreProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun signingValue(key: String, env: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)
val releaseStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.leatherbound.listening"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leatherbound.listening"
        minSdk = 26
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the debug key when no release key is configured.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.fragment)
    testImplementation(libs.junit)
}

// Libre Baskerville (SIL Open Font License) is fetched from the Google Fonts repo on first build.
// The app falls back to the system serif face if the files are missing.
val fetchFonts by tasks.registering {
    val outDir = file("src/main/assets/fonts")
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        val base = "https://raw.githubusercontent.com/google/fonts/main/ofl/librebaskerville/"
        val wanted = mapOf(
            "LibreBaskerville-Regular.ttf" to listOf("LibreBaskerville-Regular.ttf", "LibreBaskerville%5Bwght%5D.ttf"),
            "LibreBaskerville-Bold.ttf" to listOf("LibreBaskerville-Bold.ttf", "LibreBaskerville%5Bwght%5D.ttf"),
            "LibreBaskerville-Italic.ttf" to listOf("LibreBaskerville-Italic.ttf", "LibreBaskerville-Italic%5Bwght%5D.ttf"),
        )
        wanted.forEach { (target, candidates) ->
            val dest = File(outDir, target)
            if (dest.exists() && dest.length() > 0) return@forEach
            for (name in candidates) {
                try {
                    URL(base + name).openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                    logger.lifecycle("Fetched font $name -> $target")
                    break
                } catch (e: Exception) {
                    dest.delete()
                }
            }
            if (!dest.exists()) logger.warn("Could not fetch $target; the app will use the system serif font.")
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchFonts) }
