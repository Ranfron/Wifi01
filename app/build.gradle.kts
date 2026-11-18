import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "github.leavesczy.wifip2p"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "github.leavesczy.wifip2p"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    signingConfigs {
        create("release") {
            storeFile = File(rootDir.absolutePath + File.separator + "key.jks")
            keyAlias = "leavesCZY"
            storePassword = "123456"
            keyPassword = "123456"
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }
    packaging {
        jniLibs {
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
        }
        resources {
            excludes += setOf(
                "**/*.md",
                "**/*.version",
                "**/*.properties",
                "**/**/*.properties",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/CHANGES",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.12.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
    // StateFlow / Dispatchers.Main (used by the transfer hub and the foreground service)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // repeatOnLifecycle / lifecycleScope (SessionActivity collects the hub state)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // QR code: encoder + decoder (pure Java library, works on Android 6+)
    implementation("com.google.zxing:core:3.5.4")
    // Camera for the QR scanner (preview + frame analysis)
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
}