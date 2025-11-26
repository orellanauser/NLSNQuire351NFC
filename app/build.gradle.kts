plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.nlsnquire351nfc"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.nlsnquire351nfc"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Network configuration toggles and endpoints
        buildConfigField("String", "POST_URL_HTTPS", "\"https://labndevor.leoaidc.com/create\"")
        buildConfigField("String", "POST_URL_HTTP", "\"http://labndevor.leoaidc.com/create\"")
        buildConfigField("boolean", "NET_HTTP_FALLBACK_ENABLED", "false")
        buildConfigField("long", "NET_FAILURE_BACKOFF_MS", "60000L")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}