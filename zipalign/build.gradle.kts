plugins {
    `java-library`
}

// Vendored zipalign-java 1.2.2 (MIT, com.iyxan23) — pure-Java zipalign with
// zero dependencies. CLI (Main.java) intentionally excluded: this module is
// a library only. See LICENSE.zipalign-java for attribution.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
}
