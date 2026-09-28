// The Android Gradle Plugin is put on the build classpath here (instead of
// via a `plugins {}` block) so that it can be switched off with
// `-Pgains.android=false` on machines that have no Android SDK.
val androidEnabled = (findProperty("gains.android")?.toString() ?: "true").toBoolean()

buildscript {
    val androidEnabled = (findProperty("gains.android")?.toString() ?: "true").toBoolean()
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (androidEnabled) {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
    // AGP 9.4.1 brings libraries with open advisories that fail CI's Dependency review: Bouncy
    // Castle 1.80.2 (GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-c3fc-8qff-9hwx), commons-lang3
    // 3.16.0 (GHSA-j288-q9x7-2f5v) and httpclient 4.5.6 (GHSA-7r82-7xv7-xcpj). They move to the
    // catalog's versions, every Bouncy Castle module together since they are released in
    // lockstep. Drop this once AGP brings these versions or newer itself.
    configurations.classpath {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.bouncycastle" -> useVersion(libs.versions.bouncycastle.get())
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" ->
                    useVersion(libs.versions.commons.lang3.get())
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" ->
                    useVersion(libs.versions.httpclient.get())
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
}

extra["androidEnabled"] = androidEnabled

// The same for the classpaths AGP resolves in the modules themselves (lint, R8, signing).
val bouncycastleVersion = libs.versions.bouncycastle.get()
val commonsLang3Version = libs.versions.commons.lang3.get()
val httpclientVersion = libs.versions.httpclient.get()
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.bouncycastle" -> useVersion(bouncycastleVersion)
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" ->
                    useVersion(commonsLang3Version)
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" ->
                    useVersion(httpclientVersion)
            }
        }
    }
}
