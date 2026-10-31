// Pure-Kotlin/JVM module: all RF math, estimators, parsers, scoring and rule-pack
// models live here with NO Android dependency, so this module's unit tests run on a
// bare JDK + Gradle (no Android SDK, no device). The :app module wraps these with
// Android sensor/scan plumbing.
plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization") version "1.9.24"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
