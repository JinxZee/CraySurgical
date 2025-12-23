plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    // ✅ Firebase plugins (from your libs.versions.toml)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
}

android {
    namespace = "com.mail.craysurgical"

    // Keep it simple/standard (your previous compileSdk block was unusual)
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mail.craysurgical"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ✅ Use Java 17 (recommended with modern AGP)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // ✅ We are using XML layout: res/layout/activity_main.xml
        viewBinding = true

        // ❌ Compose off
        compose = false
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)

    // ✅ Needed because MainActivity extends AppCompatActivity
    //noinspection UseTomlInstead
    implementation("androidx.appcompat:appcompat:1.7.1")

    // ✅ Firebase (BoM + modules from libs.versions.toml)
    implementation(platform(libs.firebase.bom))
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)

    // ✅ Biometrics + encrypted storage
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)

    // Tests (keep)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
