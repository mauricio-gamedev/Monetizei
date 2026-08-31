plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.astromg01.monetizei"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.astromg01.monetizei"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
        buildConfigField("String", "MONETIZEI_API_BASE_URL", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
