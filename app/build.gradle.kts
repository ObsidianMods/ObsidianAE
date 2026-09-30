plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

// Global signing: single "obsidian" config from key.properties (shipped to git).
// Used for BOTH debug and release. Falls back to SDK debug key when absent.
val keyPropsFile = rootProject.file("key.properties")
val keyProps = Properties()
if (keyPropsFile.exists()) {
    keyPropsFile.inputStream().use { stream -> keyProps.load(stream) }
}
val hasSigningKey = keyPropsFile.exists()

// Ship the project's dev signing key (already committed at the repo root) as
// assets/devkey/ so ae_apk_build can sign its output out of the box. A
// key.properties + keystore staged in the MCP folder still takes priority.
val syncDevKeyAssets = tasks.register<Sync>("syncDevKeyAssets") {
    from(rootProject.file("key.properties")) { into("devkey") }
    from(rootProject.file("release.jks")) { into("devkey") }
    into(layout.buildDirectory.dir("generated/devkey-assets"))
}

android {
    namespace = "com.obsidian.apkeditor"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        if (hasSigningKey) {
            create("obsidian") {
                val storeFilePath = keyProps.getProperty("storeFile") ?: "release.jks"
                val resolved = rootProject.file(storeFilePath).let {
                    if (it.exists()) it else project.file(storeFilePath)
                }
                storeFile = resolved
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.obsidian.apkeditor"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()
    }

    buildTypes {
        debug {
            signingConfig = if (hasSigningKey) {
                signingConfigs.getByName("obsidian")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasSigningKey) {
                signingConfigs.getByName("obsidian")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        val java = JavaVersion.toVersion(libs.versions.java.get())
        sourceCompatibility = java
        targetCompatibility = java
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.java.get())
            )
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.srcDir(syncDevKeyAssets.map { it.destinationDir })

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.compose.icons.extended)

    implementation(project(":apktools"))
    implementation(project(":zipalign"))

    implementation(libs.apksig.android)

    implementation(libs.smali.dexlib2)
    implementation(libs.smali.baksmali)
    implementation(libs.smali.smali)
    implementation(libs.smali.util)
}
