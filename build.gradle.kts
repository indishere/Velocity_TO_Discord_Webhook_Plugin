/* Gradle BUILD File @ Velocity to Discord Webhook Plugin */


plugins {
    kotlin("jvm") version "2.3.0"
}

group = "com.indishere"
version = "67KB.v0.1-STABLE"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Velocity API
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    // YAML Parsing
    implementation("org.yaml:snakeyaml:2.5")

    // JSON Parsing
    implementation("com.google.code.gson:gson:2.13.2")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}
