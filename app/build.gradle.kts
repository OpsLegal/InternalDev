plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.opslegal.tda"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opslegal.tda"
        minSdk = 26
        targetSdk = 36
        // Every upload to Google Play needs a higher number. CI passes the run number.
        versionCode = (System.getenv("TDA_VERSION_CODE") ?: "2").toInt()
        versionName = "0.2.0"
        // Beta testers get every feature until the Play subscription is set up.
        // Set -Ptda.unlockAll=false for the public release.
        buildConfigField("boolean", "UNLOCK_ALL", (project.findProperty("tda.unlockAll") ?: "true").toString())
    }

    // The upload key signs what goes to Google Play (Play App Signing re-signs for phones).
    // It is never stored in the repository: it comes from environment variables.
    val keystore = System.getenv("TDA_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("upload") {
                storeFile = file(keystore)
                storePassword = System.getenv("TDA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TDA_KEY_ALIAS")
                keyPassword = System.getenv("TDA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Kept off for the beta: shrinking can break reflection at runtime and we can't test on a device here.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // A library pulls in an old Fragment; 1.3.0+ is required for the permission request API.
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
