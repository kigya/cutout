pluginManagement {
    includeBuild("node_modules/@react-native/gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.facebook.react.settings")
}

extensions.configure<com.facebook.react.ReactSettingsExtension> {
    val repo = settings.rootDir
    val node = listOf(
        "/opt/homebrew/opt/node@22/bin/node",
        "/usr/local/opt/node@22/bin/node",
    ).firstOrNull { java.io.File(it).canExecute() } ?: "node"
    autolinkLibrariesFromCommand(
        command = listOf(node, java.io.File(repo, "node_modules/@react-native-community/cli/build/bin.js").absolutePath, "config"),
        workingDirectory = repo,
        lockFiles = settings.layout.rootDirectory.files("package-lock.json", "package.json"),
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "erase-background"
include(":app")
includeBuild("node_modules/@react-native/gradle-plugin")
