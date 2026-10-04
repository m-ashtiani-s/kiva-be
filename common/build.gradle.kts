// Common: small, framework-free building blocks (Result, use case contracts, pagination, shared value objects).
// Only Kotlin plus the slf4j logging API here. Anything that needs Ktor or Exposed
// lives in src/.../infrastructure/common instead.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Logging interface only; the actual logger (logback) is chosen by the root module.
    api(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(platform(libs.coroutines.bom))
    testImplementation(libs.coroutines.test)
    // slf4j-api alone hands out a do-nothing "NOP" logger; tests need a real one.
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
