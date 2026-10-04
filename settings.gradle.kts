plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kiva-be"

include("common")
include("domain")
include("application")
