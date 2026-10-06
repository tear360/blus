import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing, in order of precedence:
 *   1. environment variables (used by GitHub Actions secrets)
 *   2. signing.properties at the project root (local dev)
 * Without either, the release APK is produced unsigned.
 */
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: signingProps.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = signingValue("BLUS_KEYSTORE", "storeFile")
val releaseStorePassword = signingValue("BLUS_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("BLUS_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("BLUS_KEY_PASSWORD", "keyPassword")

/**
 * Keystore paths come from env vars / CI, which are usually relative to the repository
 * root rather than to the :app module, so try both.
 */
val releaseKeystoreFile: java.io.File? = releaseKeystorePath?.let { path ->
    listOf(file(path), rootProject.file(path))
        .firstOrNull { it.exists() }
        ?: file(path)
}

val hasReleaseSigning = releaseKeystoreFile != null &&
    releaseKeystoreFile.exists() &&
    releaseStorePassword != null &&
    releaseKeyAlias != null

android {
    namespace = "fr.tear36.blus"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.tear36.blus"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeFromTag()
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/INDEX.LIST",
            "META-INF/*.kotlin_module",
        )
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "blus-${name}-${versionName}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.osmdroid:osmdroid-android:6.1.20")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.material:material:1.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

/** versionCode must increase on every release, so derive it from the tag when present. */
fun versionCodeFromTag(): Int {
    val tag = System.getenv("VERSION_NAME") ?: return 1
    val core = tag.substringBefore('-')
    val parts = core.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty()) return 1
    val (major, minor, patch) = parts + listOf(0, 0, 0)
    return (major.coerceAtMost(9) * 10_000) + (minor.coerceAtMost(99) * 100) + patch.coerceAtMost(99)
}