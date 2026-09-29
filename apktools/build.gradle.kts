plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.android.tools.apktools"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// apktools.xml / apktools.arsc stay pure-Java with zero dependencies.
// apktools.apk gains ONE dependency: apksig-android (public ApkVerifier
// surface only) backing ApkSignatures. apktools.dex stays shelved upstream;
// DEX lives in :app (DexIndex + smali toolchain).
dependencies {
    implementation(libs.apksig.android)
}
