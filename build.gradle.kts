plugins {
    kotlin("jvm") version "2.0.20"
}

group = "com.indishere"
version = "v0.1-BETA"

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

    // YAML -> Map
    implementation("org.yaml:snakeyaml:2.5")

    // Map -> JSON string
    implementation("tools.jackson.core:jackson-databind:3.0.3")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}
