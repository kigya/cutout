import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    jacoco
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("com.facebook.react")
}

val nodeBinary = listOf(
    "/opt/homebrew/opt/node@22/bin/node",
    "/usr/local/opt/node@22/bin/node",
).firstOrNull { file(it).canExecute() } ?: "node"

extra["REACT_NATIVE_NODE_MODULES_DIR"] = rootProject.file("node_modules/react-native").absolutePath

react {
    root.set(rootProject.layout.projectDirectory)
    nodeExecutableAndArgs.set(listOf(nodeBinary))
    autolinkLibrariesWithApp()
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "dev.kigya.cutout"
    compileSdk = 37
    ndkVersion = "27.1.12297006"
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.kigya.cutout"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            val debugStore = file(System.getProperty("user.home") + "/.android/debug.keystore")
            if (debugStore.exists()) {
                storeFile = debugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val hermesEnabled = (findProperty("hermesEnabled") ?: "true").toString().toBoolean()

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-config")
    implementation("com.facebook.react:react-android")
    if (hermesEnabled) {
        implementation("com.facebook.react:hermes-android")
    }
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.service)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.exifinterface)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidsvg)
    implementation("androidx.appcompat:appcompat:1.7.1")
    testImplementation(libs.junit)
}

jacoco { toolVersion = "0.8.14" }

val ownedClasses = fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
    include("dev/kigya/cutout/**/*.class")
    exclude("**/*_Factory*", "**/Hilt_*", "**/*_GeneratedInjector*", "**/Dagger*", "**/BuildConfig*", "**/*$" + "WhenMappings*")
}
val nativeCoverageReport by tasks.registering(JacocoReport::class) {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(ownedClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(tasks.named<Test>("testDebugUnitTest").map { it.extensions.getByType<JacocoTaskExtension>().destinationFile })
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("nativeCoverageCheck") {
    dependsOn(nativeCoverageReport)
    classDirectories.setFrom(nativeCoverageReport.get().classDirectories)
    sourceDirectories.setFrom(nativeCoverageReport.get().sourceDirectories)
    executionData.setFrom(nativeCoverageReport.get().executionData)
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "1.0".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "1.0".toBigDecimal() }
        }
    }
}
