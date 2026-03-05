plugins {
    java
}

sourceSets {
    main {
        java {
            srcDirs("src")
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":core"))

    // firmata4j — modern Java Firmata client (exclude JSSC, we use jSerialComm instead)
    implementation("com.github.kurbatov:firmata4j:2.3.8") {
        exclude(group = "org.scream3r", module = "jssc")
    }

    // jSerialComm — cross-platform serial port access (replaces JSSC, supports Apple Silicon)
    implementation("com.fazecast:jSerialComm:2.11.0")

    // SLF4J — required by firmata4j for logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")
}

tasks.register<Copy>("createLibrary") {
    dependsOn("jar")
    into(layout.buildDirectory.dir("library"))

    from(layout.projectDirectory) {
        include("library.properties")
        include("examples/**")
    }

    from(configurations.runtimeClasspath) {
        into("library")
    }

    from(tasks.jar) {
        into("library")
        rename { "arduino.jar" }
    }
}
