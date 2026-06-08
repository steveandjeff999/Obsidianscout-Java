plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
    id("com.gradleup.shadow") version "8.3.0"
}


group = "com.obsidianscout"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"
val exposedVersion = "0.53.0"
val logbackVersion = "1.5.6"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-network-tls-certificates-jvm:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("org.postgresql:postgresql:42.7.3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("at.favre.lib:bcrypt:0.10.2")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.obsidianscout.AppKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("obsidianscout-server")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

val buildBundle = tasks.register<Copy>("buildbundle") {
    group = "distribution"
    description = "Assembles a complete distribution bundle folder with the fat JAR, config files, and run scripts."
    
    dependsOn("shadowJar")
    
    // Copy the fat jar
    from(tasks.named("shadowJar")) {
        into(".")
    }
    
    // Copy the config folder
    from(file("config")) {
        into("config")
    }
    
    // Set destination directory
    into(rootProject.layout.buildDirectory.dir("bundle"))
    
    // Generate run scripts in the destination directory
    doLast {
        val bundleDir = rootProject.layout.buildDirectory.dir("bundle").get().asFile
        
        // Windows run script
        val runBat = File(bundleDir, "run.bat")
        runBat.writeText("@echo off\r\njava -jar obsidianscout-server.jar\r\npause\r\n")
        
        // Unix run script
        val runSh = File(bundleDir, "run.sh")
        runSh.writeText("#!/bin/sh\njava -jar obsidianscout-server.jar\n")
        runSh.setExecutable(true, false)
    }
}

tasks.register("buildjar") {
    group = "build"
    description = "Assembles the server fat jar and configuration bundle."
    dependsOn(buildBundle)
    doLast {
        println("=========================================================================")
        println("BUILD SUCCESSFUL")
        println("Fat JAR and Configuration bundle assembled successfully!")
        println("Location: " + rootProject.layout.buildDirectory.dir("bundle").get().asFile.absolutePath)
        println("=========================================================================")
    }
}

tasks.register("publish") {
    group = "publishing"
    description = "Assembles the server fat jar and configuration bundle for publishing/shipping."
    dependsOn("buildjar")
}



