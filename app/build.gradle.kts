import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

fun sha1SignatureHashBase64(
    storeFile: File,
    storePassword: String,
    keyAlias: String,
): String? {
    if (!storeFile.isFile) return null
    return runCatching {
        val keystore = KeyStore.getInstance(KeyStore.getDefaultType())
        storeFile.inputStream().use { keystore.load(it, storePassword.toCharArray()) }
        val cert = keystore.getCertificate(keyAlias) ?: return null
        val md = MessageDigest.getInstance("SHA-1")
        md.update(cert.encoded)
        Base64.getEncoder().encodeToString(md.digest())
    }.getOrNull()
}

fun msalRedirectUri(packageName: String, hashBase64: String): String {
    val encoded = hashBase64
        .replace("+", "%2B")
        .replace("/", "%2F")
        .replace("=", "%3D")
    return "msauth://$packageName/$encoded"
}

/** Keystore debug versionado — mesma assinatura em CI e no link do GitHub Releases. */
fun projectDebugKeystoreFile(rootDir: File): File? {
    val committed = File(rootDir, "keystore/debug.keystore")
    return committed.takeIf { it.isFile }
}

/** Fallback local (~/.android/debug.keystore) só em dev sem keystore no repo. */
fun defaultDebugKeystoreFile(): File =
    File(System.getProperty("user.home"), ".android/debug.keystore")

fun resolveDebugKeystoreFile(rootDir: File): File =
    projectDebugKeystoreFile(rootDir) ?: defaultDebugKeystoreFile()

val debugKeystoreFile = resolveDebugKeystoreFile(rootProject.projectDir)
val (msalSignatureHashBase64, msalRedirectUriForBuild) = run {
    val hash = sha1SignatureHashBase64(debugKeystoreFile, "android", "androiddebugkey")
        ?: "UNCONFIGURED"
    val packageName = "com.onegallery.sync"
    val redirect = if (hash == "UNCONFIGURED") {
        "msauth://$packageName/UNCONFIGURED"
    } else {
        msalRedirectUri(packageName, hash)
    }
    hash to redirect
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

fun readMsalClientId(): String {
    System.getenv("MSAL_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val props = Properties()
    rootProject.file("msal.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    props.getProperty("MSAL_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    props.getProperty("MSAL_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return "00000000-0000-0000-0000-000000000000"
}

val embeddedMsalClientId: String = readMsalClientId()

android {
    namespace = "com.onegallery.sync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.onegallery.sync"
        minSdk = 26
        targetSdk = 34
        versionCode = 319
        versionName = "0.8.267"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["msalSignaturePath"] = "/$msalSignatureHashBase64"

        buildConfigField("String", "MSAL_CLIENT_ID", "\"$embeddedMsalClientId\"")
        buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalSignatureHashBase64\"")
        buildConfigField("String", "MSAL_REDIRECT_URI", "\"$msalRedirectUriForBuild\"")
        buildConfigField(
            "String",
            "GRAPH_SCOPES",
            "\"User.Read Files.ReadWrite\"",
        )
    }

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.maplibre.gl:android-sdk:11.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.android.material:material:1.12.0")

    implementation("com.microsoft.identity.client:msal:5.3.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
