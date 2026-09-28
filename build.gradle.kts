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
    // AGP 9.4.1 brings Bouncy Castle 1.80.2, which has open advisories (GHSA-9pwp-9qqc-pr26,
    // GHSA-qp49-qgx5-5m26, GHSA-c3fc-8qff-9hwx) that fail CI's Dependency review. Every Bouncy
    // Castle module moves to the server's version together; they are released in lockstep.
    configurations.classpath {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion(libs.versions.bouncycastle.get())
                because("AGP's Bouncy Castle has open advisories")
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
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion(bouncycastleVersion)
                because("AGP's Bouncy Castle has open advisories")
            }
        }
    }
}
