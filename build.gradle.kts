plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "gov.nasa.jpl.parakeet"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    api("org.apache.commons:commons-math3:3.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")

    testImplementation("org.junit.jupiter:junit-jupiter-params:6.0.3")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("long-test")
    }

    minHeapSize = "1024m"
    maxHeapSize = "16g"
    jvmArgs = listOf(
        "-XX:MaxMetaspaceSize=16g",
    )
}
tasks.register<Test>("long-tests") {
    useJUnitPlatform {
        includeTags("long-test")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

// A dedicated executable fat jar. This is kept separate from the default `jar`
// task so that the plain `jar` remains a thin jar. Bundling dependencies (e.g.
// kotlinx-serialization-core) into the consumed jar shadows their version
// metadata and breaks the serialization compiler plugin in downstream projects.
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")

    manifest {
        attributes["Main-Class"] = "gov.nasa.jpl.parakeet.MainKt"
    }

    // To avoid the duplicate handling strategy error
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // To add all of the dependencies
    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
