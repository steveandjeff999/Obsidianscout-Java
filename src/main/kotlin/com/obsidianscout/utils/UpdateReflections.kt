package com.obsidianscout.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

@Serializable
data class ReflectionEntry(
    val name: String,
    val allDeclaredConstructors: Boolean = true,
    val allPublicConstructors: Boolean = true,
    val allDeclaredMethods: Boolean = true,
    val allPublicMethods: Boolean = true,
    val allDeclaredFields: Boolean? = null,
    val allPublicFields: Boolean? = null
)

fun main() {
    println("========================================================================")
    println("  ObsidianScout – Automatic Reflection Config Generator")
    println("========================================================================")

    val relativePaths = listOf(
        "src/main/resources/META-INF/native-image/com.obsidianscout/obsidianscout-server/reflect-config.json",
        "Obsidianscout/src/main/resources/META-INF/native-image/com.obsidianscout/obsidianscout-server/reflect-config.json"
    )

    val primaryFile = relativePaths.map { File(it) }.firstOrNull { it.exists() }
        ?: File("src/main/resources/META-INF/native-image/com.obsidianscout/obsidianscout-server/reflect-config.json")

    val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    // 1. Load existing entries
    val existingEntries = mutableMapOf<String, ReflectionEntry>()
    if (primaryFile.exists()) {
        try {
            val content = primaryFile.readText()
            val list = jsonParser.decodeFromString<List<ReflectionEntry>>(content)
            for (entry in list) {
                existingEntries[entry.name] = entry
            }
            println("Loaded ${existingEntries.size} existing entries from ${primaryFile.path}")
        } catch (e: Exception) {
            println("Warning: Could not parse existing reflect-config.json: ${e.message}")
        }
    }

    // 2. Discover compiled classes from build directories and runtime classpath
    val classDirCandidates = listOf(
        File("build/classes/kotlin/main"),
        File("Obsidianscout/build/classes/kotlin/main"),
        File("build/classes/java/main"),
        File("Obsidianscout/build/classes/java/main")
    )

    val discoveredClassNames = mutableSetOf<String>()
    for (classDir in classDirCandidates) {
        if (classDir.exists() && classDir.isDirectory) {
            val rootPath = classDir.toPath()
            Files.walk(rootPath).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                    .forEach { path ->
                        val rel = rootPath.relativize(path).toString()
                        val className = rel
                            .replace(File.separatorChar, '.')
                            .replace('/', '.')
                            .removeSuffix(".class")
                        discoveredClassNames.add(className)
                    }
            }
        }
    }

    println("Discovered ${discoveredClassNames.size} compiled project classes.")

    // 3. Known third-party reflection targets required for native image
    val staticReflectionTargets = listOf(
        // Exposed Database Framework
        "org.jetbrains.exposed.sql.UUIDColumnType" to true,
        "org.jetbrains.exposed.sql.ColumnType" to false,
        "org.jetbrains.exposed.sql.Table" to false,
        "org.jetbrains.exposed.sql.Column" to false,
        "org.jetbrains.exposed.dao.id.UUIDTable" to false,
        "org.jetbrains.exposed.dao.id.IdTable" to false,
        "org.jetbrains.exposed.dao.id.EntityID" to true,
        "org.jetbrains.exposed.sql.VarCharColumnType" to true,
        "org.jetbrains.exposed.sql.IntegerColumnType" to true,
        "org.jetbrains.exposed.sql.LongColumnType" to true,
        "org.jetbrains.exposed.sql.BooleanColumnType" to true,
        "org.jetbrains.exposed.sql.TextColumnType" to true,
        "org.jetbrains.exposed.sql.DoubleColumnType" to true,
        "org.jetbrains.exposed.sql.javatime.JavaTimestampColumnType" to true,

        // Ktor Sessions & Core
        "io.ktor.network.selector.InterestSuspensionsMap" to true,
        "io.ktor.server.sessions.SessionsConfig" to true,

        // Kotlinx Serialization JSON Elements
        "kotlinx.serialization.json.JsonLiteral" to true,
        "kotlinx.serialization.json.JsonLiteralSerializer" to true,
        "kotlinx.serialization.json.JsonPrimitive" to true,
        "kotlinx.serialization.json.JsonPrimitive\$Companion" to true,
        "kotlinx.serialization.json.JsonPrimitiveSerializer" to true,
        "kotlinx.serialization.json.JsonObject" to true,
        "kotlinx.serialization.json.JsonObject\$Companion" to true,
        "kotlinx.serialization.json.JsonObjectSerializer" to true,
        "kotlinx.serialization.json.JsonArray" to true,
        "kotlinx.serialization.json.JsonArray\$Companion" to true,
        "kotlinx.serialization.json.JsonArraySerializer" to true,
        "kotlinx.serialization.json.JsonElement" to true,
        "kotlinx.serialization.json.JsonElement\$Companion" to true,
        "kotlinx.serialization.json.JsonElementSerializer" to true,
        "kotlinx.serialization.json.JsonNull" to true,
        "kotlinx.serialization.json.JsonNull\$Companion" to true,
        "kotlinx.serialization.json.JsonNullSerializer" to true,
        "kotlinx.serialization.json.internal.JsonTreeElementSerializer" to true,

        // JDBC Drivers & Connection Pooling
        "org.sqlite.JDBC" to true,
        "org.postgresql.Driver" to true,
        "com.zaxxer.hikari.HikariConfig" to true,
        "com.zaxxer.hikari.HikariDataSource" to true,

        // Mail / SMTP (Angus & Jakarta Mail)
        "org.eclipse.angus.mail.smtp.SMTPTransport" to true,
        "org.eclipse.angus.mail.smtp.SMTPSSLTransport" to true,
        "jakarta.mail.Session" to true,
        "jakarta.mail.Transport" to true,

        // Security / Crypto / WebPush (BouncyCastle & EC Specs)
        "org.bouncycastle.jce.provider.BouncyCastleProvider" to true,
        "java.security.interfaces.ECPublicKey" to true,
        "java.security.interfaces.ECPrivateKey" to true,
        "java.security.spec.ECGenParameterSpec" to true,
        "java.security.spec.ECParameterSpec" to true,
        "java.security.spec.ECPoint" to true,
        "java.security.spec.ECPublicKeySpec" to true,
        "java.security.spec.ECPrivateKeySpec" to true,
        "java.security.spec.PKCS8EncodedKeySpec" to true,
        "java.security.spec.RSAPrivateCrtKeySpec" to true,
        "java.security.spec.RSAPrivateKeySpec" to true,
        "java.security.interfaces.RSAPrivateKey" to true,
        "java.security.interfaces.RSAPrivateCrtKey" to true
    )

    for ((target, includeFields) in staticReflectionTargets) {
        if (!existingEntries.containsKey(target)) {
            existingEntries[target] = ReflectionEntry(
                name = target,
                allDeclaredConstructors = true,
                allPublicConstructors = true,
                allDeclaredMethods = true,
                allPublicMethods = true,
                allDeclaredFields = if (includeFields) true else null,
                allPublicFields = if (includeFields) true else null
            )
        }
    }

    // 4. Process all project classes
    var addedCount = 0
    val targetClassPrefixes = listOf(
        "com.obsidianscout.routes.",
        "com.obsidianscout.analytics.",
        "com.obsidianscout.config.",
        "com.obsidianscout.scouting.",
        "com.obsidianscout.db.",
        "com.obsidianscout.integrations.",
        "com.obsidianscout.auth.",
        "com.obsidianscout.admin.",
        "com.obsidianscout.model.",
        "com.obsidianscout.utils."
    )

    for (className in discoveredClassNames) {
        // Skip synthetic compiler classes like lambda closures ($1, $2, inlined)
        if (className.matches(Regex(".*\\$\\d+$")) ||
            className.contains("\$inlined\$") ||
            className.contains("\$sam\$")
        ) {
            continue
        }

        val isTargetPrefix = targetClassPrefixes.any { className.startsWith(it) }
        val isSerializer = className.contains("\$\$serializer")
        val isCompanion = className.endsWith("\$Companion")

        // Include any model, serializer, companion, database table, or configuration class
        if (isTargetPrefix || isSerializer || isCompanion) {
            // Determine if fields should be included
            val includeFields = isSerializer ||
                    isCompanion ||
                    className.startsWith("com.obsidianscout.routes.") ||
                    className.startsWith("com.obsidianscout.analytics.") ||
                    className.startsWith("com.obsidianscout.config.") ||
                    className.startsWith("com.obsidianscout.scouting.") ||
                    className.startsWith("com.obsidianscout.integrations.") ||
                    className.startsWith("com.obsidianscout.auth.") ||
                    className.startsWith("com.obsidianscout.db.")

            if (!existingEntries.containsKey(className)) {
                existingEntries[className] = ReflectionEntry(
                    name = className,
                    allDeclaredConstructors = true,
                    allPublicConstructors = true,
                    allDeclaredMethods = true,
                    allPublicMethods = true,
                    allDeclaredFields = if (includeFields) true else null,
                    allPublicFields = if (includeFields) true else null
                )
                addedCount++
            }
        }
    }

    println("Added $addedCount new/missing reflection entries.")

    // 5. Serialize entries cleanly to json
    val finalEntries = existingEntries.values.toList()

    val sb = StringBuilder()
    sb.append("[\n")
    for (i in finalEntries.indices) {
        val e = finalEntries[i]
        sb.append("  {\n")
        sb.append("    \"name\": \"${e.name}\",\n")
        sb.append("    \"allDeclaredConstructors\": ${e.allDeclaredConstructors},\n")
        sb.append("    \"allPublicConstructors\": ${e.allPublicConstructors},\n")
        sb.append("    \"allDeclaredMethods\": ${e.allDeclaredMethods},\n")
        if (e.allDeclaredFields != null || e.allPublicFields != null) {
            sb.append("    \"allPublicMethods\": ${e.allPublicMethods},\n")
            if (e.allDeclaredFields != null && e.allPublicFields != null) {
                sb.append("    \"allDeclaredFields\": ${e.allDeclaredFields},\n")
                sb.append("    \"allPublicFields\": ${e.allPublicFields}\n")
            } else if (e.allDeclaredFields != null) {
                sb.append("    \"allDeclaredFields\": ${e.allDeclaredFields}\n")
            } else {
                sb.append("    \"allPublicFields\": ${e.allPublicFields}\n")
            }
        } else {
            sb.append("    \"allPublicMethods\": ${e.allPublicMethods}\n")
        }
        if (i < finalEntries.size - 1) {
            sb.append("  },\n")
        } else {
            sb.append("  }\n")
        }
    }
    sb.append("]\n")

    val jsonOutput = sb.toString()

    // 6. Write to standard resource locations
    val currentDir = File(".").canonicalFile
    val baseProjectDir = if (File(currentDir, "Obsidianscout").isDirectory && File(currentDir, "settings.gradle.kts").exists()) {
        File(currentDir, "Obsidianscout")
    } else {
        currentDir
    }

    val targetFiles = listOf(
        File(baseProjectDir, "src/main/resources/META-INF/native-image/com.obsidianscout/obsidianscout-server/reflect-config.json"),
        File(baseProjectDir, "build/resources/main/META-INF/native-image/com.obsidianscout/obsidianscout-server/reflect-config.json")
    )

    for (targetFile in targetFiles) {
        val parent = targetFile.parentFile
        if (parent != null && (parent.exists() || targetFile.path.contains("src" + File.separator + "main"))) {
            parent.mkdirs()
            targetFile.writeText(jsonOutput)
            println("Updated: ${targetFile.absolutePath}")
        }
    }

    println("========================================================================")
    println("SUCCESS: Total configured reflection classes: ${finalEntries.size}")
    println("========================================================================")
}
