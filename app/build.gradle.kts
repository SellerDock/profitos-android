import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing secrets live outside the repo on purpose (never commit
// keystore or passwords). Point PROFITOS_KEYSTORE_PROPERTIES (env) or the
// Gradle property profitos.keystoreProperties at a keystore.properties file
// with storeFile/storePassword/keyAlias/keyPassword.
val releaseKeystorePropsFile = file(
    System.getenv("PROFITOS_KEYSTORE_PROPERTIES")
        ?: (project.findProperty("profitos.keystoreProperties") as String?)
        ?: "/opt/profitos/apk-release/keystore.properties"
)

// Instance the app opens. Override for a self-hosted server, e.g.
// ./gradlew assembleDebug -Pprofitos.startUrl=https://app.example.com/dashboard -Pprofitos.allowedDomain=example.com
val startUrl = (project.findProperty("profitos.startUrl") as String?) ?: "https://app.profitos.pl/dashboard"
val allowedDomain = (project.findProperty("profitos.allowedDomain") as String?) ?: "profitos.pl"
require(startUrl.startsWith("https://")) { "profitos.startUrl must use https://" }
val releaseKeystoreProps = Properties().apply {
    if (releaseKeystorePropsFile.exists()) {
        releaseKeystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = releaseKeystorePropsFile.exists()

android {
    namespace = "pl.profitos.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.profitos.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"
        buildConfigField("String", "START_URL", "\"$startUrl\"")
        buildConfigField("String", "ALLOWED_DOMAIN", "\"$allowedDomain\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}

// Fail loudly (instead of silently falling back to an unsigned/debug APK)
// when someone runs assembleRelease/bundleRelease without the release
// keystore config present on this machine.
if (!hasReleaseSigning) {
    afterEvaluate {
        tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") || it.name.startsWith("packageRelease") }
            .configureEach {
                doFirst {
                    throw GradleException(
                        "Release signing config not found at " +
                            "${releaseKeystorePropsFile.absolutePath}. " +
                            "Cannot build a release APK without the release keystore " +
                            "properties file (storeFile/storePassword/keyAlias/keyPassword). " +
                            "Refusing to fall back to debug signing."
                    )
                }
            }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
