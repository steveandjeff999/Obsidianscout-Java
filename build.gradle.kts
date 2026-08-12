plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("com.gradleup.shadow") version "9.3.0"
    id("org.graalvm.buildtools.native") version "0.10.4"
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
    implementation("com.google.firebase:firebase-admin:9.4.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}

application {
    mainClass.set("com.obsidianscout.AppKt")
    applicationDefaultJvmArgs = listOf(
        "-Djava.awt.headless=true",
        "-Xms256m",
        "-Xmx2048m",
        "-XX:+AlwaysPreTouch",
        "-XX:+UseStringDeduplication",
        "-XX:InitiatingHeapOccupancyPercent=35",
        "-XX:G1HeapWastePercent=5",
        "-XX:SoftRefLRUPolicyMSPerMB=0",
        "-XX:MaxMetaspaceSize=256m",
        "-Xss256k",
        "-Dio.netty.allocator.maxOrder=8",
        "-XX:MaxDirectMemorySize=128m"
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
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
    mustRunAfter("bumpVersion")
    
    from(tasks.named("shadowJar")) {
        into(".")
    }
    
    from(file("config")) {
        into("config")
    }
    
    from(file("../docs")) {
        into("docs")
    }

    from(file("scripts/update.sh")) { into(".") }
    from(file("scripts/update.bat")) { into(".") }
    from(file("scripts/configure_ntp.sh")) { into(".") }
    from(file("scripts/configure_ntp.ps1")) { into(".") }
    from(file("scripts/install-graal.sh")) { into(".") }
    from(file("scripts/install-graal.bat")) { into(".") }
    from(file("scripts/install-graal.ps1")) { into(".") }

    into(rootProject.layout.buildDirectory.dir("bundle"))
    
    doLast {
        val bundleDir = rootProject.layout.buildDirectory.dir("bundle").get().asFile

        val runBat = File(bundleDir, "run.bat")
        runBat.writeText(
            "@echo off\r\n" +
            "setlocal enabledelayedexpansion\r\n" +
            "if \"%LOW_RAM%\"==\"1\" set IS_LOW_RAM=1\r\n" +
            "if \"%1\"==\"--low-ram\" set IS_LOW_RAM=1\r\n" +
            ":loop\r\n" +
            "set JAVA_OPTS=\r\n" +
            "if \"!IS_LOW_RAM!\"==\"1\" (\r\n" +
            "    set HEAP_SIZE=512m\r\n" +
            "    if exist .oom_occurred (\r\n" +
            "        del /q .oom_occurred >nul 2>&1\r\n" +
            "        set HEAP_SIZE=768m\r\n" +
            "        echo [OOM-Recovery] Low-RAM OutOfMemoryError detected! Capping max heap at 768m to protect Pi 4B physical RAM...\r\n" +
            "    ) else (\r\n" +
            "        echo [ObsidianScout] Low-RAM/Low-CPU mode enabled. Dynamic heap ^(256m to 512m^)...\r\n" +
            "    )\r\n" +
            "    set JAVA_OPTS=-Djava.awt.headless=true -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 -XX:CompileThreshold=1500 -Xms256m -Xmx!HEAP_SIZE! -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:SoftRefLRUPolicyMSPerMB=0 -XX:GCTimeRatio=4 -XX:MaxMetaspaceSize=128m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=5 -Dio.netty.eventLoopThreads=2 -XX:MaxDirectMemorySize=32m\r\n" +
            ") else (\r\n" +
            "    if \"%HEAP_SIZE%\"==\"\" set HEAP_SIZE=2048m\r\n" +
            "    if exist .oom_occurred (\r\n" +
            "        del /q .oom_occurred >nul 2>&1\r\n" +
            "        if \"!HEAP_SIZE!\"==\"1024m\" set HEAP_SIZE=1536m\r\n" +
            "        if \"!HEAP_SIZE!\"==\"1536m\" set HEAP_SIZE=2048m\r\n" +
            "        if \"!HEAP_SIZE!\"==\"2048m\" set HEAP_SIZE=3072m\r\n" +
            "        if \"!HEAP_SIZE!\"==\"3072m\" set HEAP_SIZE=4096m\r\n" +
            "        echo [OOM-Recovery] Auto-escalating max heap RAM to !HEAP_SIZE! with thorough GC protections...\r\n" +
            "        set JAVA_OPTS=-Djava.awt.headless=true -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 -XX:CompileThreshold=1500 -Xms256m -Xmx!HEAP_SIZE! -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:SoftRefLRUPolicyMSPerMB=0 -XX:GCTimeRatio=4 -XX:MaxMetaspaceSize=192m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=6 -Dio.netty.eventLoopThreads=2 -XX:MaxDirectMemorySize=64m\r\n" +
            "    ) else if \"%USE_ZGC%\"==\"1\" (\r\n" +
            "        echo [ObsidianScout] Standard mode. Ultra-low latency Generational ZGC ^(512m to !HEAP_SIZE!^)...\r\n" +
            "        set JAVA_OPTS=-Djava.awt.headless=true -XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx!HEAP_SIZE! -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:SoftRefLRUPolicyMSPerMB=0 -XX:MaxMetaspaceSize=256m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=8 -XX:MaxDirectMemorySize=128m\r\n" +
            "    ) else (\r\n" +
            "        echo [ObsidianScout] Standard mode. Dynamic heap ^(512m to !HEAP_SIZE!^) with thorough G1GC...\r\n" +
            "        set JAVA_OPTS=-Djava.awt.headless=true -Xms512m -Xmx!HEAP_SIZE! -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:InitiatingHeapOccupancyPercent=35 -XX:G1HeapWastePercent=5 -XX:SoftRefLRUPolicyMSPerMB=0 -XX:MaxMetaspaceSize=256m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=8 -XX:MaxDirectMemorySize=128m\r\n" +
            "    )\r\n" +
            ")\r\n" +
            "set JAVA_EXEC=java\r\n" +
            "set GRAAL_JAVA=%USERPROFILE%\\.graalvm\\graalvm-jdk-21\\bin\\java.exe\r\n" +
            "if not exist \"!GRAAL_JAVA!\" (\r\n" +
            "    if defined GRAALVM_HOME if exist \"%GRAALVM_HOME%\\bin\\java.exe\" set GRAAL_JAVA=%GRAALVM_HOME%\\bin\\java.exe\r\n" +
            ")\r\n" +
            "if not exist \"!GRAAL_JAVA!\" (\r\n" +
            "    echo [ObsidianScout] GraalVM JDK 21 not detected. Auto-installing GraalVM for high-performance execution...\r\n" +
            "    if exist install-graal.bat call install-graal.bat\r\n" +
            "    if exist \"%USERPROFILE%\\.graalvm\\graalvm-jdk-21\\bin\\java.exe\" set GRAAL_JAVA=%USERPROFILE%\\.graalvm\\graalvm-jdk-21\\bin\\java.exe\r\n" +
            ")\r\n" +
            "if exist \"!GRAAL_JAVA!\" (\r\n" +
            "    echo [ObsidianScout] Using GraalVM High-Performance JVM: !GRAAL_JAVA!\r\n" +
            "    set JAVA_EXEC=\"!GRAAL_JAVA!\"\r\n" +
            ")\r\n" +
            "%JAVA_EXEC% %JAVA_OPTS% -jar obsidianscout-server.jar\r\n" +
            "set EXIT_CODE=%ERRORLEVEL%\r\n" +
            "if \"%EXIT_CODE%\"==\"3\" echo 1 > .oom_occurred\r\n" +
            "if \"%EXIT_CODE%\"==\"137\" echo 1 > .oom_occurred\r\n" +
            "timeout /t 3 >nul 2>&1\r\n" +
            "if exist .update_pending (\r\n" +
            "    echo [Updater] FAULTY INSTALLATION DETECTED! Server exited with code !EXIT_CODE! while update was pending testing.\r\n" +
            "    echo [Updater] Initiating automatic rollback to previous working version...\r\n" +
            "    if exist .backup\\obsidianscout-server.jar (\r\n" +
            "        copy /y \".backup\\obsidianscout-server.jar\" \".\" >nul\r\n" +
            "        if exist .backup\\config\\app-config.json (\r\n" +
            "            if not exist config mkdir config\r\n" +
            "            copy /y \".backup\\config\\app-config.json\" \"config\\\" >nul\r\n" +
            "        )\r\n" +
            "        for %%s in (run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1) do (\r\n" +
            "            if exist \".backup\\%%s\" copy /y \".backup\\%%s\" \".\" >nul\r\n" +
            "        )\r\n" +
            "        echo [Updater] Rollback successful. Restored previous working version from .backup.\r\n" +
            "    ) else (\r\n" +
            "        echo [Updater] ERROR: Backup directory .backup is missing!\r\n" +
            "    )\r\n" +
            "    del /q .update_pending >nul 2>&1\r\n" +
            "    del /q .update_result >nul 2>&1\r\n" +
            "    goto loop\r\n" +
            ")\r\n" +
            "if exist .update_result (\r\n" +
            "    set /p SRC_ROOT=<.update_result\r\n" +
            "    del /q .update_result\r\n" +
            "    if exist \"!SRC_ROOT!\" (\r\n" +
            "        echo [Updater] Backing up current installation before applying update...\r\n" +
            "        if not exist .backup mkdir .backup\r\n" +
            "        if exist obsidianscout-server.jar copy /y \"obsidianscout-server.jar\" \".backup\\\" >nul\r\n" +
            "        if exist config\\app-config.json (\r\n" +
            "            if not exist .backup\\config mkdir .backup\\config\r\n" +
            "            copy /y \"config\\app-config.json\" \".backup\\config\\\" >nul\r\n" +
            "        )\r\n" +
            "        for %%s in (run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1) do (\r\n" +
            "            if exist \"%%s\" copy /y \"%%s\" \".backup\\\" >nul\r\n" +
            "        )\r\n" +
            "        echo [Updater] Applying update from !SRC_ROOT!...\r\n" +
            "        copy /y \"!SRC_ROOT!\\obsidianscout-server.jar\" \".\" >nul\r\n" +
            "        for %%s in (run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1) do (\r\n" +
            "            if exist \"!SRC_ROOT!\\%%s\" copy /y \"!SRC_ROOT!\\%%s\" \".\" >nul\r\n" +
            "        )\r\n" +
            "        for %%i in (\"!SRC_ROOT!\\..\") do set TEMP_DIR=%%~fi\r\n" +
            "        rd /s /q \"!TEMP_DIR!\"\r\n" +
            "        if exist .update_tmp rd /s /q .update_tmp >nul 2>&1\r\n" +
            "        echo pending > .update_pending\r\n" +
            "        echo [Updater] Update applied. Restarting to test boot...\r\n" +
            "    )\r\n" +
            ")\r\n" +
            "goto loop\r\n"
        )

        val runSh = File(bundleDir, "run.sh")
        runSh.writeText(
            "#!/bin/sh\n" +
            "if [ \"\$LOW_RAM\" = \"1\" ] || [ \"\$LOW_MEM\" = \"1\" ] || [ \"\$1\" = \"--low-ram\" ]; then\n" +
            "    IS_LOW_RAM=1\n" +
            "fi\n" +
            "if grep -q zram /proc/swaps 2>/dev/null; then\n" +
            "    echo \"[zRAM] Ultra-fast compressed RAM swap detected & active!\"\n" +
            "elif [ \"\$IS_LOW_RAM\" = \"1\" ] && [ -f ./setup-zram.sh ]; then\n" +
            "    if [ \"\$(id -u)\" -eq 0 ]; then\n" +
            "        echo \"[zRAM] Auto-configuring 1024MB compressed RAM device...\"\n" +
            "        ./setup-zram.sh\n" +
            "    elif command -v sudo >/dev/null 2>&1; then\n" +
            "        echo \"[zRAM] Auto-configuring compressed RAM swap via sudo...\"\n" +
            "        sudo ./setup-zram.sh 2>/dev/null || echo \"[zRAM] Tip: Run 'sudo ./setup-zram.sh' manually to activate zRAM.\"\n" +
            "    fi\n" +
            "fi\n" +
            "while true; do\n" +
            "    JAVA_OPTS=\"\"\n" +
            "    if [ \"\$IS_LOW_RAM\" = \"1\" ]; then\n" +
            "        HEAP_SIZE=\"512m\"\n" +
            "        if [ -f .oom_occurred ]; then\n" +
            "            rm -f .oom_occurred\n" +
            "            HEAP_SIZE=\"768m\"\n" +
            "            echo \"[OOM-Recovery] Low-RAM OutOfMemoryError detected! Capping max heap at 768m to protect Pi 4B physical RAM...\"\n" +
            "        else\n" +
            "            echo \"[ObsidianScout] Low-RAM/Low-CPU mode enabled. Dynamic heap (256m to 512m)...\"\n" +
            "        fi\n" +
            "        JAVA_OPTS=\"-Djava.awt.headless=true -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 -XX:CompileThreshold=1500 -Xms256m -Xmx\$HEAP_SIZE -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:SoftRefLRUPolicyMSPerMB=0 -XX:GCTimeRatio=4 -XX:MaxMetaspaceSize=128m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=5 -Dio.netty.eventLoopThreads=2 -XX:MaxDirectMemorySize=32m\"\n" +
            "    else\n" +
            "        HEAP_SIZE=\"\${HEAP_SIZE:-2048m}\"\n" +
            "        if [ -f .oom_occurred ]; then\n" +
            "            rm -f .oom_occurred\n" +
            "            case \"\$HEAP_SIZE\" in\n" +
            "                \"1024m\") HEAP_SIZE=\"1536m\" ;;\n" +
            "                \"1536m\") HEAP_SIZE=\"2048m\" ;;\n" +
            "                \"2048m\") HEAP_SIZE=\"3072m\" ;;\n" +
            "                \"3072m\") HEAP_SIZE=\"4096m\" ;;\n" +
            "                *) HEAP_SIZE=\"2048m\" ;;\n" +
            "            esac\n" +
            "            echo \"[OOM-Recovery] Auto-escalating max heap RAM to \$HEAP_SIZE with thorough GC protections...\"\n" +
            "            JAVA_OPTS=\"-Djava.awt.headless=true -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 -XX:CompileThreshold=1500 -Xms256m -Xmx\$HEAP_SIZE -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:SoftRefLRUPolicyMSPerMB=0 -XX:GCTimeRatio=4 -XX:MaxMetaspaceSize=192m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=6 -Dio.netty.eventLoopThreads=2 -XX:MaxDirectMemorySize=64m\"\n" +
            "        elif [ \"\$USE_ZGC\" = \"1\" ]; then\n" +
            "            echo \"[ObsidianScout] Standard mode. Ultra-low latency Generational ZGC (512m to \$HEAP_SIZE)...\"\n" +
            "            JAVA_OPTS=\"-Djava.awt.headless=true -XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx\$HEAP_SIZE -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:SoftRefLRUPolicyMSPerMB=0 -XX:MaxMetaspaceSize=256m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=8 -XX:MaxDirectMemorySize=128m\"\n" +
            "        else\n" +
            "            echo \"[ObsidianScout] Standard mode. Dynamic heap (512m to \$HEAP_SIZE) with thorough G1GC...\"\n" +
            "            JAVA_OPTS=\"-Djava.awt.headless=true -Xms512m -Xmx\$HEAP_SIZE -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -XX:InitiatingHeapOccupancyPercent=35 -XX:G1HeapWastePercent=5 -XX:SoftRefLRUPolicyMSPerMB=0 -XX:MaxMetaspaceSize=256m -Xss256k -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=8 -XX:MaxDirectMemorySize=128m\"\n" +
            "        fi\n" +
            "    fi\n" +
            "    GRAAL_JAVA=\"\$HOME/.graalvm/graalvm-jdk-21/bin/java\"\n" +
            "    if [ ! -x \"\$GRAAL_JAVA\" ] && [ -n \"\$GRAALVM_HOME\" ] && [ -x \"\$GRAALVM_HOME/bin/java\" ]; then\n" +
            "        GRAAL_JAVA=\"\$GRAALVM_HOME/bin/java\"\n" +
            "    fi\n" +
            "    if [ ! -x \"\$GRAAL_JAVA\" ]; then\n" +
            "        echo \"[ObsidianScout] GraalVM JDK 21 not detected. Auto-installing GraalVM for high-performance execution...\"\n" +
            "        if [ -x ./install-graal.sh ]; then\n" +
            "            ./install-graal.sh\n" +
            "        elif [ -x ./scripts/install-graal.sh ]; then\n" +
            "            ./scripts/install-graal.sh\n" +
            "        fi\n" +
            "        if [ -x \"\$HOME/.graalvm/graalvm-jdk-21/bin/java\" ]; then\n" +
            "            GRAAL_JAVA=\"\$HOME/.graalvm/graalvm-jdk-21/bin/java\"\n" +
            "        fi\n" +
            "    fi\n" +
            "    if [ -x \"\$GRAAL_JAVA\" ]; then\n" +
            "        echo \"[ObsidianScout] Using GraalVM High-Performance JVM: \$GRAAL_JAVA\"\n" +
            "        JAVA_EXEC=\"\$GRAAL_JAVA\"\n" +
            "    else\n" +
            "        echo \"[ObsidianScout] Falling back to system default Java...\"\n" +
            "        JAVA_EXEC=\"java\"\n" +
            "    fi\n" +
            "    \"\$JAVA_EXEC\" \$JAVA_OPTS -jar obsidianscout-server.jar\n" +
            "    EXIT_CODE=\$?\n" +
            "    if [ \$EXIT_CODE -eq 3 ] || [ \$EXIT_CODE -eq 137 ]; then\n" +
            "        touch .oom_occurred\n" +
            "    fi\n" +
            "    sleep 3\n" +
            "    if [ -f .update_pending ]; then\n" +
            "        echo \"[Updater] FAULTY INSTALLATION DETECTED! Server exited with code \$EXIT_CODE while update was pending testing.\"\n" +
            "        echo \"[Updater] Initiating automatic rollback to previous working version...\"\n" +
            "        if [ -f .backup/obsidianscout-server.jar ]; then\n" +
            "            cp .backup/obsidianscout-server.jar ./\n" +
            "            if [ -f .backup/config/app-config.json ]; then\n" +
            "                mkdir -p config\n" +
            "                cp .backup/config/app-config.json config/\n" +
            "            fi\n" +
            "            for script in run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1; do\n" +
            "                if [ -f \".backup/\$script\" ]; then\n" +
            "                    cp \".backup/\$script\" ./\n" +
            "                    chmod +x \"./\$script\" 2>/dev/null || true\n" +
            "                fi\n" +
            "            done\n" +
            "            echo \"[Updater] Rollback successful. Restored previous working version from .backup.\"\n" +
            "        else\n" +
            "            echo \"[Updater] ERROR: Backup directory .backup is missing!\"\n" +
            "        fi\n" +
            "        rm -f .update_pending .update_result\n" +
            "        continue\n" +
            "    fi\n" +
            "    if [ -f .update_result ]; then\n" +
            "        SRC_ROOT=\$(cat .update_result)\n" +
            "        rm -f .update_result\n" +
            "        if [ -d \"\$SRC_ROOT\" ]; then\n" +
            "            echo \"[Updater] Backing up current installation before applying update...\"\n" +
            "            mkdir -p .backup .backup/config\n" +
            "            if [ -f obsidianscout-server.jar ]; then\n" +
            "                cp obsidianscout-server.jar .backup/\n" +
            "            fi\n" +
            "            if [ -f config/app-config.json ]; then\n" +
            "                cp config/app-config.json .backup/config/\n" +
            "            fi\n" +
            "            for script in run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1; do\n" +
            "                if [ -f \"\$script\" ]; then\n" +
            "                    cp \"\$script\" .backup/\n" +
            "                fi\n" +
            "            done\n" +
            "            echo \"[Updater] Applying update from \$SRC_ROOT...\"\n" +
            "            cp \"\$SRC_ROOT/obsidianscout-server.jar\" ./\n" +
            "            for script in run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1; do\n" +
            "                if [ -f \"\$SRC_ROOT/\$script\" ]; then\n" +
            "                    cp \"\$SRC_ROOT/\$script\" ./\n" +
            "                    chmod +x \"./\$script\" 2>/dev/null || true\n" +
            "                fi\n" +
            "            done\n" +
            "            TEMP_DIR=\$(dirname \"\$SRC_ROOT\")\n" +
            "            rm -rf \"\$TEMP_DIR\"\n" +
            "            rm -rf .update_tmp 2>/dev/null || true\n" +
            "            echo pending > .update_pending\n" +
            "            echo \"[Updater] Update applied. Restarting to test boot...\"\n" +
            "        fi\n" +
            "    fi\n" +
            "done\n"
        )
        runSh.setExecutable(true, false)

        val resetBat = File(bundleDir, "reset-superadmin.bat")
        resetBat.writeText(
            "@echo off\r\n" +
            "java -cp obsidianscout-server.jar com.obsidianscout.utils.ResetSuperAdminKt %*\r\n" +
            "pause\r\n"
        )

        val resetSh = File(bundleDir, "reset-superadmin.sh")
        resetSh.writeText("#!/bin/sh\njava -cp obsidianscout-server.jar com.obsidianscout.utils.ResetSuperAdminKt \"\$@\"\n")
        resetSh.setExecutable(true, false)

        val updateSh = File(bundleDir, "update.sh")
        if (updateSh.exists()) {
            updateSh.setExecutable(true, false)
        }

        val configFile = File(bundleDir, "config/app-config.json")
        if (configFile.exists()) {
            var text = configFile.readText()
            val secretFields = listOf(
                "sessionSecret",
                "keystorePassword",
                "adminPassword",
                "password",
                "db_password",
                "google_sheet_password"
            )
            for (field in secretFields) {
                text = text.replace(
                    Regex("(\"$field\"\\s*:\\s*)\"[^\"]*\""),
                    "\$1\"changeme\""
                )
            }
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

fun incrementVersion(version: String): String {
    val parts = version.split(".").mapNotNull { it.toIntOrNull() }.toMutableList()
    while (parts.size < 4) parts.add(0)

    var carry = 1
    for (i in parts.size - 1 downTo 0) {
        val sum = parts[i] + carry
        parts[i] = sum % 10
        carry = sum / 10
        if (carry == 0) break
    }
    return parts.joinToString(".")
}

tasks.register("bumpVersion") {
    group = "publishing"
    description = "Auto-increments current_version in config/app-config.json and AppConfig.kt before bundling."
    dependsOn("regenerateConfigs")
    onlyIf { !project.hasProperty("skipBump") }
    doLast {
        val configFile = file("config/app-config.json")
        val appConfigKt = file("src/main/kotlin/com/obsidianscout/config/AppConfig.kt")
        val outerConfigFile = file("../config/app-config.json")

        val versionRegex = Regex("\"current_version\"\\s*:\\s*\"([^\"]+)\"")
        val configText = configFile.readText()
        val match = versionRegex.find(configText)
            ?: error("[bumpVersion] Could not find \"current_version\" in ${configFile.absolutePath}")

        val oldVersion = match.groupValues[1]
        val newVersion = incrementVersion(oldVersion)
        println("[bumpVersion] $oldVersion -> $newVersion")

        configFile.writeText(
            configText.replace(match.value, "\"current_version\": \"$newVersion\"")
        )
        println("[bumpVersion] Updated ${configFile.absolutePath}")

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

val javaToolchains = project.extensions.getByType<JavaToolchainService>()

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        named("main") {
            imageName.set("obsidianscout-server-native")
            mainClass.set("com.obsidianscout.AppKt")
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                }
            )
            buildArgs.addAll(
                "-H:+UnlockExperimentalVMOptions",
                "-H:+ReportExceptionStackTraces",
                "-H:EnableURLProtocols=http,https",
                "--no-fallback",
                "-Djava.awt.headless=true",
                "--initialize-at-build-time=kotlin.",
                "--initialize-at-build-time=kotlin.reflect.",
                "--initialize-at-build-time=org.jetbrains.exposed."
            )
        }
    }
}



val nativeArchs = listOf("arm64", "arm32", "x86", "x86_64", "riscv64", "ppc64le", "s390x")

val nativeBundleTasks = nativeArchs.map { arch ->
    val taskName = "buildNativeBundle${arch.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
    tasks.register<Copy>(taskName) {
        group = "distribution"
        description = "Assembles native binary distribution bundle folder for architecture: $arch"

        dependsOn("shadowJar", "regenerateConfigs", "bumpVersion")
        if (project.hasProperty("withNativeImage") || System.getenv("GRAALVM_HOME") != null) {
            tasks.findByName("nativeCompile")?.let { dependsOn(it) }
        }
        mustRunAfter("bumpVersion")

        from(layout.buildDirectory.dir("native/nativeCompile")) {
            into(".")
        }

        from(tasks.named("shadowJar")) {
            into(".")
        }

        from(file("config")) {
            into("config")
        }

        from(file("../docs")) {
            into("docs")
        }

        from(file("scripts/update.sh")) { into(".") }
        from(file("scripts/update.bat")) { into(".") }
        from(file("scripts/configure_ntp.sh")) { into(".") }
        from(file("scripts/configure_ntp.ps1")) { into(".") }
        from(file("scripts/install-graal.sh")) { into(".") }
        from(file("scripts/install-graal.bat")) { into(".") }
        from(file("scripts/install-graal.ps1")) { into(".") }

        into(rootProject.layout.buildDirectory.dir("bundle-native/$arch"))

        doLast {
            val bundleDir = rootProject.layout.buildDirectory.dir("bundle-native/$arch").get().asFile

            val nativeWin = File(bundleDir, "obsidianscout-server-native.exe")
            val nativeUnix = File(bundleDir, "obsidianscout-server-native")
            if (nativeWin.exists()) {
                val archWin = File(bundleDir, "obsidianscout-server-native-$arch.exe")
                if (!archWin.exists()) nativeWin.copyTo(archWin, overwrite = true)
            }
            if (nativeUnix.exists()) {
                val archUnix = File(bundleDir, "obsidianscout-server-native-$arch")
                if (!archUnix.exists()) nativeUnix.copyTo(archUnix, overwrite = true)
                archUnix.setExecutable(true, false)
            }

            val runBat = File(bundleDir, "run.bat")
            runBat.writeText(
                "@echo off\r\n" +
                "setlocal enabledelayedexpansion\r\n" +
                "set BINARY_NAME=obsidianscout-server-native-$arch.exe\r\n" +
                "if exist \"!BINARY_NAME!\" (\r\n" +
                "    set EXEC_CMD=!BINARY_NAME!\r\n" +
                ") else if exist obsidianscout-server-native.exe (\r\n" +
                "    set EXEC_CMD=obsidianscout-server-native.exe\r\n" +
                ") else if exist obsidianscout-server-native-$arch (\r\n" +
                "    set EXEC_CMD=obsidianscout-server-native-$arch\r\n" +
                ") else (\r\n" +
                "    echo [ObsidianScout Native] Native binary not found, checking GraalVM JDK for high-performance JVM execution...\r\n" +
                "    set GRAAL_JAVA=%USERPROFILE%\\.graalvm\\graalvm-jdk-21\\bin\\java.exe\r\n" +
                "    if not exist \"!GRAAL_JAVA!\" (\r\n" +
                "        if defined GRAALVM_HOME if exist \"%GRAALVM_HOME%\\bin\\java.exe\" set GRAAL_JAVA=%GRAALVM_HOME%\\bin\\java.exe\r\n" +
                "    )\r\n" +
                "    if not exist \"!GRAAL_JAVA!\" (\r\n" +
                "        echo [ObsidianScout] Auto-installing GraalVM JDK 21 for high-performance execution...\r\n" +
                "        if exist install-graal.bat call install-graal.bat\r\n" +
                "        if exist \"%USERPROFILE%\\.graalvm\\graalvm-jdk-21\\bin\\java.exe\" set GRAAL_JAVA=%USERPROFILE%\\.graalvm\\graalvm-jdk-21\\bin\\java.exe\r\n" +
                "    )\r\n" +
                "    if exist \"!GRAAL_JAVA!\" (\r\n" +
                "        echo [ObsidianScout] Using GraalVM High-Performance JVM: !GRAAL_JAVA!\r\n" +
                "        set EXEC_CMD=\"!GRAAL_JAVA!\" -Djava.awt.headless=true -Xms256m -Xmx1024m -jar obsidianscout-server.jar\r\n" +
                "    ) else (\r\n" +
                "        set EXEC_CMD=java -Djava.awt.headless=true -Xms256m -Xmx1024m -jar obsidianscout-server.jar\r\n" +
                "    )\r\n" +
                ")\r\n" +
                ":loop\r\n" +
                "echo [ObsidianScout Native] Starting server ($arch native binary)...\r\n" +
                "%EXEC_CMD%\r\n" +
                "set EXIT_CODE=%ERRORLEVEL%\r\n" +
                "timeout /t 3 >nul 2>&1\r\n" +
                "if exist .update_pending (\r\n" +
                "    echo [Updater] Faulty installation detected! Server exited with code !EXIT_CODE! while update was pending testing.\r\n" +
                "    echo [Updater] Initiating automatic rollback...\r\n" +
                "    if exist .backup\\obsidianscout-server-native-$arch (\r\n" +
                "        copy /y \".backup\\obsidianscout-server-native-$arch\" \".\" >nul\r\n" +
                "    ) else if exist .backup\\obsidianscout-server.jar (\r\n" +
                "        copy /y \".backup\\obsidianscout-server.jar\" \".\" >nul\r\n" +
                "    )\r\n" +
                "    del /q .update_pending >nul 2>&1\r\n" +
                "    del /q .update_result >nul 2>&1\r\n" +
                "    goto loop\r\n" +
                ")\r\n" +
                "if exist .update_result (\r\n" +
                "    set /p SRC_ROOT=<.update_result\r\n" +
                "    del /q .update_result\r\n" +
                "    if exist \"!SRC_ROOT!\" (\r\n" +
                "        echo [Updater] Backing up current native installation...\r\n" +
                "        if not exist .backup mkdir .backup\r\n" +
                "        if exist obsidianscout-server-native-$arch copy /y \"obsidianscout-server-native-$arch\" \".backup\\\" >nul\r\n" +
                "        if exist obsidianscout-server.jar copy /y \"obsidianscout-server.jar\" \".backup\\\" >nul\r\n" +
                "        echo [Updater] Applying native update from !SRC_ROOT!...\r\n" +
                "        copy /y \"!SRC_ROOT!\\*\" \".\" >nul\r\n" +
                "        echo pending > .update_pending\r\n" +
                "        echo [Updater] Update applied. Restarting native server...\r\n" +
                "    )\r\n" +
                ")\r\n" +
                "goto loop\r\n"
            )

            val runSh = File(bundleDir, "run.sh")
            runSh.writeText(
                "#!/bin/sh\n" +
                "BINARY_NAME=\"obsidianscout-server-native-$arch\"\n" +
                "if [ -f \"./\$BINARY_NAME\" ]; then\n" +
                "    EXEC_CMD=\"./\$BINARY_NAME\"\n" +
                "elif [ -f \"./obsidianscout-server-native\" ]; then\n" +
                "    EXEC_CMD=\"./obsidianscout-server-native\"\n" +
                "else\n" +
                "    echo \"[ObsidianScout Native] Native binary not found, checking GraalVM JDK for high-performance JVM execution...\"\n" +
                "    GRAAL_JAVA=\"\$HOME/.graalvm/graalvm-jdk-21/bin/java\"\n" +
                "    if [ ! -x \"\$GRAAL_JAVA\" ] && [ -n \"\$GRAALVM_HOME\" ] && [ -x \"\$GRAALVM_HOME/bin/java\" ]; then\n" +
                "        GRAAL_JAVA=\"\$GRAALVM_HOME/bin/java\"\n" +
                "    fi\n" +
                "    if [ ! -x \"\$GRAAL_JAVA\" ]; then\n" +
                "        echo \"[ObsidianScout] Auto-installing GraalVM JDK 21 for high-performance execution...\"\n" +
                "        if [ -x ./install-graal.sh ]; then\n" +
                "            ./install-graal.sh\n" +
                "        fi\n" +
                "        if [ -x \"\$HOME/.graalvm/graalvm-jdk-21/bin/java\" ]; then\n" +
                "            GRAAL_JAVA=\"\$HOME/.graalvm/graalvm-jdk-21/bin/java\"\n" +
                "        fi\n" +
                "    fi\n" +
                "    if [ -x \"\$GRAAL_JAVA\" ]; then\n" +
                "        echo \"[ObsidianScout] Using GraalVM High-Performance JVM: \$GRAAL_JAVA\"\n" +
                "        EXEC_CMD=\"\\\"\$GRAAL_JAVA\\\" -Djava.awt.headless=true -Xms256m -Xmx1024m -jar obsidianscout-server.jar\"\n" +
                "    else\n" +
                "        EXEC_CMD=\"java -Djava.awt.headless=true -Xms256m -Xmx1024m -jar obsidianscout-server.jar\"\n" +
                "    fi\n" +
                "fi\n" +
                "while true; do\n" +
                "    echo \"[ObsidianScout Native] Starting server ($arch native binary)...\"\n" +
                "    \$EXEC_CMD\n" +
                "    EXIT_CODE=\$?\n" +
                "    sleep 3\n" +
                "    if [ -f .update_pending ]; then\n" +
                "        echo \"[Updater] Faulty installation detected! Exited code \$EXIT_CODE while update was pending.\"\n" +
                "        echo \"[Updater] Restoring backup...\"\n" +
                "        if [ -f .backup/\$BINARY_NAME ]; then\n" +
                "            cp .backup/\$BINARY_NAME ./\n" +
                "        elif [ -f .backup/obsidianscout-server.jar ]; then\n" +
                "            cp .backup/obsidianscout-server.jar ./\n" +
                "        fi\n" +
                "        rm -f .update_pending .update_result\n" +
                "        continue\n" +
                "    fi\n" +
                "    if [ -f .update_result ]; then\n" +
                "        SRC_ROOT=\$(cat .update_result)\n" +
                "        rm -f .update_result\n" +
                "        if [ -d \"\$SRC_ROOT\" ]; then\n" +
                "            echo \"[Updater] Applying update from \$SRC_ROOT...\"\n" +
                "            cp -R \"\$SRC_ROOT\"/* ./\n" +
                "            echo pending > .update_pending\n" +
                "        fi\n" +
                "    fi\n" +
                "done\n"
            )
            runSh.setExecutable(true, false)

            val resetBat = File(bundleDir, "reset-superadmin.bat")
            resetBat.writeText(
                "@echo off\r\n" +
                "java -cp obsidianscout-server.jar com.obsidianscout.utils.ResetSuperAdminKt %*\r\n" +
                "pause\r\n"
            )

            val resetSh = File(bundleDir, "reset-superadmin.sh")
            resetSh.writeText("#!/bin/sh\njava -cp obsidianscout-server.jar com.obsidianscout.utils.ResetSuperAdminKt \"\$@\"\n")
            resetSh.setExecutable(true, false)

            val configFile = File(bundleDir, "config/app-config.json")
            var currentVer = ""
            if (configFile.exists()) {
                var text = configFile.readText()
                val verMatch = Regex("\"current_version\"\\s*:\\s*\"([^\"]+)\"").find(text)
                if (verMatch != null) currentVer = verMatch.groupValues[1]

                val secretFields = listOf(
                    "sessionSecret",
                    "keystorePassword",
                    "adminPassword",
                    "password",
                    "db_password",
                    "google_sheet_password"
                )
                for (field in secretFields) {
                    text = text.replace(
                        Regex("(\"$field\"\\s*:\\s*)\"[^\"]*\""),
                        "\$1\"changeme\""
                    )
                }
                text = text.replace(
                    Regex("(\"type\"\\s*:\\s*)\"[^\"]*\""),
                    "\$1\"sqlite\""
                )
                configFile.writeText(text)
            }

            if (currentVer.isNotEmpty()) {
                val versionedDir = rootProject.layout.buildDirectory.dir("bundle-native/obsidianscout-v$currentVer-$arch").get().asFile
                bundleDir.copyRecursively(versionedDir, overwrite = true)
                println("[publishnative] Created versioned bundle folder: ${versionedDir.absolutePath}")
            }
        }
    }
}

val buildNativeBundles = tasks.register("buildNativeBundles") {
    group = "distribution"
    description = "Assembles native binary distribution bundles for all architectures (" + nativeArchs.joinToString(", ") + ")."
    dependsOn(nativeBundleTasks)
}

tasks.register("publishnative") {
    group = "publishing"
    description = "Bumps version and builds native binary distribution bundles for target architectures."
    dependsOn("bumpVersion", buildNativeBundles)
    doLast {
        val configFile = file("config/app-config.json")
        val verMatch = if (configFile.exists()) Regex("\"current_version\"\\s*:\\s*\"([^\"]+)\"").find(configFile.readText()) else null
        val verStr = verMatch?.groupValues?.get(1) ?: ""

        println("=========================================================================")
        println("NATIVE PUBLISH SUCCESSFUL")
        if (verStr.isNotEmpty()) {
            println("Version: v$verStr")
        }
        println("Native binary distribution bundles assembled for target architectures:")
        nativeArchs.forEach { arch ->
            val folderName = if (verStr.isNotEmpty()) "obsidianscout-v$verStr-$arch" else arch
            println("  - %-8s: %s".format(arch, rootProject.layout.buildDirectory.dir("bundle-native/$folderName").get().asFile.absolutePath))
        }
        println("=========================================================================")
    }
}
