plugins {
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.8" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("com.facebook.react.rootproject")
}

extra.apply {
    set("REACT_NATIVE_NODE_MODULES_DIR", file("node_modules/react-native").absolutePath)
    set("buildToolsVersion", "37.0.0")
    set("minSdkVersion", 26)
    set("compileSdkVersion", 37)
    set("targetSdkVersion", 36)
    set("ndkVersion", "27.1.12297006")
    set("kotlinVersion", "2.2.10")
}

// Gradle launched by desktop tooling may not inherit an interactive Node PATH.
allprojects {
    tasks.withType<Exec>().configureEach {
        doFirst {
            if (commandLine.firstOrNull() == "node") {
                val node = listOf("/opt/homebrew/opt/node@22/bin/node", "/usr/local/opt/node@22/bin/node")
                    .firstOrNull { file(it).canExecute() }
                if (node != null) executable = node
            }
        }
    }
}
