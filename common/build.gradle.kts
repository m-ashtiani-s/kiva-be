// Common: small, framework-free building blocks (Result, use case contracts, pagination, shared value objects).
// Only Kotlin here. Anything that needs Ktor or Exposed lives in src/.../infrastructure/common instead.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
