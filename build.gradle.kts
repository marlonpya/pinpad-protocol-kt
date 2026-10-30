plugins {
    kotlin("jvm") version "2.2.10"
    `java-library`
    `maven-publish`
    application
}

group = "io.github.marlonpya"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // El simulador cifra el PIN con DUKPT como lo haría el hardware seguro.
    api("io.github.marlonpya:dukpt-kt:0.1.0")
    // El PINpad firma los mensajes ISO 8583 con Retail MAC.
    implementation("io.github.marlonpya:iso8583-kt:0.1.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

application {
    mainClass.set("io.github.marlonpya.pinpad.simulator.SimulatorMainKt")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
