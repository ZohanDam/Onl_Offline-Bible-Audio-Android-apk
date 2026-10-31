plugins {
    id("com.android.application")
}

val externalBuildDir = providers.gradleProperty("ngheBuildDir").orNull
    ?: providers.environmentVariable("NGHEKINHTHANH_BUILD_DIR").orNull
layout.buildDirectory.set(if (externalBuildDir != null) file(externalBuildDir) else file("build2"))

android {
    namespace = "com.zohan.nghekinhthanh1925"
    compileSdk = 35

    val localDebugSigning = providers.gradleProperty("debugKeystore").orNull?.let { path ->
        signingConfigs.create("localDebug") {
            storeFile = file(path)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.zohan.nghekinhthanh1925"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "2.27"
    }

    buildTypes {
        getByName("debug") {
            if (localDebugSigning != null) {
                signingConfig = localDebugSigning
            }
        }
        getByName("release") {
            isDebuggable = false
            if (localDebugSigning != null) signingConfig = localDebugSigning
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
