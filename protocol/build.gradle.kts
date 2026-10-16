plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The live test against a running SillHost reads these; without them it is skipped.
    listOf("SILL_LIVE_HOST", "SILL_LIVE_PORT", "SILL_LIVE_CODE_FILE", "SILL_LIVE_DIR").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
