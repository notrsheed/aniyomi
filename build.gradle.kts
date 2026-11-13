buildscript {
    repositories { maven("https://storage.googleapis.com/r8-releases/raw") }
    dependencies { classpath("com.android.tools:r8:8.9.35") }
    dependencies {
        classpath(libs.android.shortcut.gradle)
    }
}

plugins {
    alias(kotlinx.plugins.serialization) apply false
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.moko) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}


