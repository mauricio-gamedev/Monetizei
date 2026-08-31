plugins {
    id("com.android.application")
}

val signingStorePath = System.getenv("MONETIZEI_KEYSTORE_PATH")
val signingStorePassword = System.getenv("MONETIZEI_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("MONETIZEI_KEY_ALIAS")
val signingKeyPassword = System.getenv("MONETIZEI_KEY_PASSWORD")
val stableSigningConfigured = listOf(
    signingStorePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.astromg01.monetizei"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.astromg01.monetizei"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "0.6.0"
        buildConfigField(
            "String",
            "MONETIZEI_API_BASE_URL",
            "\"https://monetizei-production.up.railway.app\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    val stableUpdateSigning = if (stableSigningConfigured) {
        signingConfigs.create("stableUpdate") {
            storeFile = file(signingStorePath!!)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        debug {
            stableUpdateSigning?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            stableUpdateSigning?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":protocol"))
    testImplementation("junit:junit:4.13.2")
}
