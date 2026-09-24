// Plugins are put on the root classpath so the Kotlin and Android plugins share one
// classloader. The Android plugin is only needed (and only resolvable) where an
// Android SDK is installed; see settings.gradle.kts.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        val kotlinVersion = "2.2.21"
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
            System.getenv("ANDROID_SDK_ROOT") != null ||
            file("local.properties").exists()
        if (hasAndroidSdk) classpath("com.android.tools.build:gradle:8.13.0")
    }
}
