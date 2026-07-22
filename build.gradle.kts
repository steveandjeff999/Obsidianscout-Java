plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("com.gradleup.shadow") version "9.3.0"
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
    implementation("io.netty:netty-tcnative-boringssl-static:2.0.65.Final")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-caching-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
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
    implementation("com.auth0:java-jwt:4.4.0")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.eclipse.angus:jakarta.mail:2.0.3")
    implementation("nl.martijndwars:web-push:5.1.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}

application {
    mainClass.set("com.obsidianscout.AppKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
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
    
    dependsOn("shadowJar", "regenerateConfigs", "bumpVersion")
    // bumpVersion must run after regenerateConfigs so it can overwrite the freshly-generated
    // config with the bumped version before buildBundle copies it into the bundle folder.
    mustRunAfter("bumpVersion")
    
    // Copy the fat jar
    from(tasks.named("shadowJar")) {
        into(".")
    }
    
    // Copy the config folder
    from(file("config")) {
        into("config")
    }
    
    // Copy the docs folder
    from(file("../docs")) {
        into("docs")
    }

    // Copy the update scripts
    from(file("scripts/update.sh")) {
        into(".")
    }
    from(file("scripts/update.bat")) {
        into(".")
    }
    from(file("scripts/configure_ntp.sh")) {
        into(".")
    }
    from(file("scripts/configure_ntp.ps1")) {
        into(".")
    }
    
    // Set destination directory
    into(rootProject.layout.buildDirectory.dir("bundle"))
    
    // Generate run scripts in the destination directory
    doLast {
        val bundleDir = rootProject.layout.buildDirectory.dir("bundle").get().asFile

        // Windows run script — loops and applies staged updates automatically
        val runBat = File(bundleDir, "run.bat")
        runBat.writeText(
            "@echo off\r\n" +
            "setlocal enabledelayedexpansion\r\n" +
            ":loop\r\n" +
            "java -jar obsidianscout-server.jar\r\n" +
            "if exist .update_result (\r\n" +
            "    set /p SRC_ROOT=<.update_result\r\n" +
            "    del /q .update_result\r\n" +
            "    if exist \"!SRC_ROOT!\" (\r\n" +
            "        echo [Updater] Applying update from !SRC_ROOT!...\r\n" +
            "        copy /y \"!SRC_ROOT!\\obsidianscout-server.jar\" \".\" >nul\r\n" +
            "        for %%s in (run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat) do (\r\n" +
            "            if exist \"!SRC_ROOT!\\%%s\" copy /y \"!SRC_ROOT!\\%%s\" \".\" >nul\r\n" +
            "        )\r\n" +
            "        for %%i in (\"!SRC_ROOT!\\..\") do set TEMP_DIR=%%~fi\r\n" +
            "        rd /s /q \"!TEMP_DIR!\"\r\n" +
            "        echo [Updater] Update applied. Restarting...\r\n" +
            "    )\r\n" +
            ")\r\n" +
            "goto loop\r\n"
        )

        // Unix run script — loops and applies staged updates automatically
        val runSh = File(bundleDir, "run.sh")
        runSh.writeText(
            "#!/bin/sh\n" +
            "while true; do\n" +
            "    java -jar obsidianscout-server.jar\n" +
            "    if [ -f .update_result ]; then\n" +
            "        SRC_ROOT=\$(cat .update_result)\n" +
            "        rm -f .update_result\n" +
            "        if [ -d \"\$SRC_ROOT\" ]; then\n" +
            "            echo \"[Updater] Applying update from \$SRC_ROOT...\"\n" +
            "            cp \"\$SRC_ROOT/obsidianscout-server.jar\" ./\n" +
            "            for script in run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat; do\n" +
            "                if [ -f \"\$SRC_ROOT/\$script\" ]; then\n" +
            "                    cp \"\$SRC_ROOT/\$script\" ./\n" +
            "                    chmod +x \"./\$script\" 2>/dev/null || true\n" +
            "                fi\n" +
            "            done\n" +
            "            TEMP_DIR=\$(dirname \"\$SRC_ROOT\")\n" +
            "            rm -rf \"\$TEMP_DIR\"\n" +
            "            echo \"[Updater] Update applied. Restarting...\"\n" +
            "        fi\n" +
            "    fi\n" +
            "done\n"
        )
        runSh.setExecutable(true, false)

        // Windows reset-superadmin script
        val resetBat = File(bundleDir, "reset-superadmin.bat")
        resetBat.writeText(
            "@echo off\r\n" +
            "java -cp obsidianscout-server.jar com.obsidianscout.utils.ResetSuperAdminKt %*\r\n" +
            "pause\r\n"
        )

        // Unix reset-superadmin script
        val resetSh = File(bundleDir, "reset-superadmin.sh")
        resetSh.writeText("#!/bin/sh\njava -cp obsidianscout-server.jar com.obsidianscout.utils.ResetSuperAdminKt \"\$@\"\n")
        resetSh.setExecutable(true, false)

        // Unix update script
        val updateSh = File(bundleDir, "update.sh")
        if (updateSh.exists()) {
            updateSh.setExecutable(true, false)
        }


        // Sanitize secrets in the copied config — reset every known secret field
        // to "changeme" so the bundle ships with clear, consistent placeholders.
        // The server auto-generates real random secrets on first startup (except
        // adminPassword and postgres password, which must be set manually).
        val configFile = File(bundleDir, "config/app-config.json")
        if (configFile.exists()) {
            var text = configFile.readText()
            // Each replacement targets a specific JSON key so unrelated values are
            // never accidentally clobbered.
            val secretFields = listOf(
                "sessionSecret",
                "keystorePassword",
                "adminPassword",
                "password",   // covers database.postgres.password
                "db_password",
                "google_sheet_password"
            )
            for (field in secretFields) {
                // Matches: "fieldName": "<any value>" and replaces the value with "changeme"
                text = text.replace(
                    Regex("(\"$field\"\\s*:\\s*)\"[^\"]*\""),
                    "\$1\"changeme\""
                )
            }
            // Set database type to sqlite
            text = text.replace(
                Regex("(\"type\"\\s*:\\s*)\"[^\"]*\""),
                "\$1\"sqlite\""
            )
            configFile.writeText(text)
            println("Sanitized secrets in bundle config: ${configFile.absolutePath}")
        }
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

/**
 * Increments a 4-part version string (e.g. "0.2.4.7") by adding 1 to the last segment.
 * Carry rolls left through all segments — the first segment increments normally and never caps.
 *
 * Examples:
 *   0.2.4.9  -> 0.2.5.0
 *   0.2.9.9  -> 0.3.0.0
 *   0.9.9.9  -> 1.0.0.0
 *   9.9.9.9  -> 10.0.0.0  (first segment goes above 9 — no further wrapping)
 */
fun incrementVersion(version: String): String {
    val parts = version.split(".").mapNotNull { it.toIntOrNull() }.toMutableList()
    while (parts.size < 4) parts.add(0)

    var carry = 1
    for (i in parts.size - 1 downTo 0) { // carry propagates through all segments, including index 0
        val sum = parts[i] + carry
        parts[i] = sum % 10
        carry = sum / 10
        if (carry == 0) break
    }
    // If carry still remains after index 0 (e.g. a single-segment "9" + 1), it is dropped
    // since there is no 5th place — but in practice a 4-part version can never overflow here
    // because 9.9.9.9 + 1 = 10.0.0.0 which is representable in the same 4 parts.
    return parts.joinToString(".")
}

/**
 * Bumps the current_version in config/app-config.json (and the outer workspace copy) and
 * updates the default string literal in AppConfig.kt so future regenerateConfigs calls are
 * in sync. Must run AFTER regenerateConfigs (which would otherwise overwrite the file with
 * the stale default) and BEFORE buildBundle (which copies the config into the bundle).
 */
tasks.register("bumpVersion") {
    group = "publishing"
    description = "Auto-increments current_version in config/app-config.json and AppConfig.kt before bundling."
    dependsOn("regenerateConfigs") // ensure regenerateConfigs has already written its output first
    doLast {
        val configFile = file("config/app-config.json")
        val appConfigKt = file("src/main/kotlin/com/obsidianscout/config/AppConfig.kt")
        val outerConfigFile = file("../config/app-config.json")

        // ── Read & bump the version ───────────────────────────────────────────────
        val versionRegex = Regex("\"current_version\"\\s*:\\s*\"([^\"]+)\"")
        val configText = configFile.readText()
        val match = versionRegex.find(configText)
            ?: error("[bumpVersion] Could not find \"current_version\" in ${configFile.absolutePath}")

        val oldVersion = match.groupValues[1]
        val newVersion = incrementVersion(oldVersion)
        println("[bumpVersion] $oldVersion -> $newVersion")

        // ── Patch config/app-config.json ─────────────────────────────────────────
        configFile.writeText(
            configText.replace(match.value, "\"current_version\": \"$newVersion\"")
        )
        println("[bumpVersion] Updated ${configFile.absolutePath}")

        // ── Patch outer workspace config if it exists ─────────────────────────────
        if (outerConfigFile.exists()) {
            val outerText = outerConfigFile.readText()
            val outerMatch = versionRegex.find(outerText)
            if (outerMatch != null) {
                outerConfigFile.writeText(
                    outerText.replace(outerMatch.value, "\"current_version\": \"$newVersion\"")
                )
                println("[bumpVersion] Updated ${outerConfigFile.absolutePath}")
            }
        }

        // ── Patch AppConfig.kt default so regenerateConfigs stays in sync next time ─
        val ktText = appConfigKt.readText()
        val ktRegex = Regex("(val current_version: String = \")[^\"]+(\")")
        val patchedKt = ktRegex.replace(ktText) { matchResult ->
            matchResult.groupValues[1] + newVersion + matchResult.groupValues[2]
        }
        if (patchedKt != ktText) {
            appConfigKt.writeText(patchedKt)
            println("[bumpVersion] Updated default in ${appConfigKt.absolutePath}")
        }
    }
}

tasks.register("publish") {
    group = "publishing"
    description = "Bumps the version, assembles the server fat jar and configuration bundle for publishing/shipping."
    dependsOn("bumpVersion", "buildjar")
}

tasks.register<JavaExec>("verifyMobile") {
    group = "verification"
    description = "Runs the mobile API verification script."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.obsidianscout.utils.VerifyMobileApiKt")
}

tasks.register<JavaExec>("dumpSettings") {
    group = "verification"
    description = "Dumps app settings from database."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.obsidianscout.utils.DumpSettingsKt")
}

tasks.register<JavaExec>("regenerateConfigs") {
    group = "build"
    description = "Regenerates all default configuration files from updated defaults in the code."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.obsidianscout.utils.RegenerateConfigsKt")
}





