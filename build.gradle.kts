plugins {
    kotlin("jvm") version "1.9.0"
    id("maven-publish")
}

group = "dev.hlwgroup.ktor"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api("io.ktor:ktor-server-core:2.3.1")
    implementation("com.google.guava:guava:31.1-jre")
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.hlwgroup.ktor"
            artifactId = "ktor-response-cache"
            version = "1.0.0"

            from(components["java"])
        }
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
