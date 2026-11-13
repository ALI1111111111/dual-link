import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Play upload key: key.properties (git-ignored) points at a keystore kept outside the repo.
// `-Psideload` keeps the old debug signature so already-installed test phones can update in place.
val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val useUploadKey = !keyProps.isEmpty && !project.hasProperty("sideload")

android {
    namespace = "com.duallink.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.duallink.app"
        minSdk = 26 // Android 8+: needed for java.util.Base64 and modern background rules
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (useUploadKey) create("upload") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (useUploadKey) "upload" else "debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}


dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Phone A talks to its own Wireless debugging (localhost only) to start the voice helper.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3") // TLS 1.3 for ADB pairing
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81") // same version libadb uses; builds our cert
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
