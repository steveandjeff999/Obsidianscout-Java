@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.obsidianscout.utils

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Scanner
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

import kotlinx.serialization.ExperimentalSerializationApi

/**
 * UpdateHelper Utility
 *
 * Handles fetching releases, user interaction, downloading, extraction,
 * and configuration merging in a cross-platform manner.
 */
fun main() {
    val scanner = Scanner(System.`in`)
    
    println("=================================================================")
    println("                 Obsidianscout Update Utility                    ")
    println("=================================================================")
    
    // Display Disclaimer
    println()
    println("=================================================================")
    println("                            DISCLAIMER                           ")
    println("=================================================================")
    println("  - Downgrading to an older version is NOT supported.")
    println("  - Downgrading may require a database reset if the schema")
    println("    is incompatible with older versions.")
    println("  - It is highly recommended to backup your config/ and data/")
    println("    directories before proceeding.")
    println("=================================================================")
    println()
    
    print("Do you want to proceed with searching for available updates? (y/N): ")
    val confirmSearch = scanner.nextLine().trim()
    if (!confirmSearch.equalsIgnoreCase("y") && !confirmSearch.equalsIgnoreCase("yes")) {
        println("Update aborted.")
        exitProcess(0)
    }
    
    println("\nFetching releases from GitHub...")
    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.github.com/repos/steveandjeff999/Obsidianscout-Java/releases"))
        .header("Accept", "application/vnd.github.v3+json")
        .build()
        
    val response = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: Exception) {
        System.err.println("Error: Failed to fetch releases. Please check your internet connection.")
        System.err.println("Details: ${e.message}")
        exitProcess(1)
    }
    
    if (response.statusCode() != 200) {
        System.err.println("Error: GitHub API returned status code ${response.statusCode()}")
        exitProcess(1)
    }
    
    val releasesJson = response.body()
    val jsonArray = try {
        Json.parseToJsonElement(releasesJson).jsonArray
    } catch (e: Exception) {
        System.err.println("Error parsing releases JSON: ${e.message}")
        exitProcess(1)
    }
    
    val releases = mutableListOf<ReleaseInfo>()
    for (element in jsonArray) {
        val obj = element.jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.content ?: continue
        val isPrerelease = obj["prerelease"]?.jsonPrimitive?.boolean ?: false
        
        // Find zip asset download URL
        var zipUrl = ""
        val assets = obj["assets"]?.jsonArray
        if (assets != null) {
            for (asset in assets) {
                val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                if (name.endsWith(".zip")) {
                    zipUrl = asset.jsonObject["browser_download_url"]?.jsonPrimitive?.content ?: ""
                    break
                }
            }
        }
        if (zipUrl.isEmpty()) {
            zipUrl = obj["zipball_url"]?.jsonPrimitive?.content ?: ""
        }
        
        if (zipUrl.isNotEmpty()) {
            releases.add(ReleaseInfo(tag, isPrerelease, zipUrl))
        }
    }
    
    if (releases.isEmpty()) {
        println("No available versions found.")
        exitProcess(0)
    }
    
    println("\nAvailable Versions:")
    println("-------------------")
    for (i in releases.indices) {
        val r = releases[i]
        val typeStr = if (r.isPrerelease) "[PRERELEASE]" else "[RELEASE]"
        println("  ${i + 1}) ${r.tag}  $typeStr")
    }
    println("-------------------")
    
    var selection = -1
    while (true) {
        print("Select a version to install (1-${releases.size}): ")
        val input = scanner.nextLine().trim()
        val num = input.toIntOrNull()
        if (num != null && num in 1..releases.size) {
            selection = num - 1
            break
        }
        System.err.println("Invalid selection.")
    }
    
    val selectedRelease = releases[selection]
    
    if (selectedRelease.isPrerelease) {
        println("\nWARNING: You selected a PRERELEASE version (${selectedRelease.tag}).")
        println("Prereleases may be unstable or contain bugs.")
        print("Are you sure you want to install this version? (y/N): ")
        val confirmPre = scanner.nextLine().trim()
        if (!confirmPre.equalsIgnoreCase("y") && !confirmPre.equalsIgnoreCase("yes")) {
            println("Update aborted.")
            exitProcess(0)
        }
    }
    
    print("Are you absolutely sure you want to update to ${selectedRelease.tag}? (y/N): ")
    val confirmFinal = scanner.nextLine().trim()
    if (!confirmFinal.equalsIgnoreCase("y") && !confirmFinal.equalsIgnoreCase("yes")) {
        println("Update aborted.")
        exitProcess(0)
    }
    
    // Create temp directory
    val tempDir = try {
        Files.createTempDirectory("obsidianscout-update-").toFile()
    } catch (e: Exception) {
        System.err.println("Failed to create temporary directory: ${e.message}")
        exitProcess(1)
    }
    
    val zipFile = File(tempDir, "update.zip")
    println("\nDownloading update from: ${selectedRelease.url}")
    
    val zipRequest = HttpRequest.newBuilder().uri(URI.create(selectedRelease.url)).build()
    val zipResponse = try {
        client.send(zipRequest, HttpResponse.BodyHandlers.ofFile(zipFile.toPath()))
    } catch (e: Exception) {
        System.err.println("Download failed: ${e.message}")
        tempDir.deleteRecursively()
        exitProcess(1)
    }
    
    if (zipResponse.statusCode() != 200) {
        System.err.println("Error: Failed to download update. HTTP status: ${zipResponse.statusCode()}")
        tempDir.deleteRecursively()
        exitProcess(1)
    }
    
    println("Extracting bundle...")
    val extractDir = File(tempDir, "extracted")
    try {
        unzip(zipFile, extractDir)
    } catch (e: Exception) {
        System.err.println("Extraction failed: ${e.message}")
        tempDir.deleteRecursively()
        exitProcess(1)
    }
    
    val srcRoot = extractDir.walkTopDown()
        .firstOrNull { it.isFile && it.name.equals("obsidianscout-server.jar", ignoreCase = true) }
        ?.parentFile
        
    if (srcRoot == null) {
        System.err.println("Error: Could not find obsidianscout-server.jar in the extracted update bundle.")
        tempDir.deleteRecursively()
        exitProcess(1)
    }
    
    println("Merging configurations...")
    val srcConfig = File(srcRoot, "config")
    if (srcConfig.exists() && srcConfig.isDirectory) {
        val destConfig = File("config")
        destConfig.mkdirs()
        
        srcConfig.walkFileTree().forEach { srcFile ->
            if (srcFile.isFile) {
                val relPath = srcFile.relativeTo(srcConfig).path
                val userFile = File(destConfig, relPath)
                
                userFile.parentFile?.mkdirs()
                
                if (srcFile.name.endsWith(".json")) {
                    if (userFile.exists() && userFile.length() > 0) {
                        println("Merging configuration schema changes for config/$relPath...")
                        try {
                            val userJson = Json.parseToJsonElement(userFile.readText())
                            val defaultJson = Json.parseToJsonElement(srcFile.readText())
                            val merged = deepMerge(userJson, defaultJson)
                            
                            val prettyJson = Json { prettyPrint = true }
                            userFile.writeText(prettyJson.encodeToString(JsonElement.serializer(), merged) + "\n")
                        } catch (e: Exception) {
                            println("Warning: Failed to merge config/$relPath, overwriting with default. Details: ${e.message}")
                            srcFile.copyTo(userFile, overwrite = true)
                        }
                    } else {
                        println("Adding new default config file config/$relPath...")
                        srcFile.copyTo(userFile)
                    }
                } else {
                    // Non-JSON files (e.g. Caddyfile, nginx.conf)
                    if (userFile.exists()) {
                        println("Preserving customized config/$relPath (new template saved as config/$relPath.new)")
                        srcFile.copyTo(File(destConfig, "$relPath.new"), overwrite = true)
                    } else {
                        println("Adding new config template config/$relPath...")
                        srcFile.copyTo(userFile)
                    }
                }
            }
        }
    }
    
    // Copy docs
    val srcDocs = File(srcRoot, "docs")
    if (srcDocs.exists() && srcDocs.isDirectory) {
        println("Updating documentation...")
        val destDocs = File("docs")
        destDocs.deleteRecursively()
        srcDocs.copyRecursively(destDocs, overwrite = true)
    }
    
    // Write the temp dir path to a file for the wrapper scripts to read
    try {
        File(".update_result").writeText(srcRoot.absolutePath)
    } catch (e: Exception) {
        System.err.println("Failed to write update result: ${e.message}")
        tempDir.deleteRecursively()
        exitProcess(1)
    }
}

private data class ReleaseInfo(val tag: String, val isPrerelease: Boolean, val url: String)

private fun String.equalsIgnoreCase(other: String): Boolean = this.equals(other, ignoreCase = true)

private fun unzip(zipFile: File, destDir: File) {
    destDir.mkdirs()
    ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val entryName = entry.name.replace('\\', '/')
            val file = File(destDir, entryName)
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    zip.copyTo(out)
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
}

private fun File.walkFileTree(): List<File> {
    val list = mutableListOf<File>()
    fun walk(f: File) {
        list.add(f)
        if (f.isDirectory) {
            f.listFiles()?.forEach { walk(it) }
        }
    }
    walk(this)
    return list
}

private fun deepMerge(user: JsonElement, default: JsonElement): JsonElement {
    if (user is JsonObject && default is JsonObject) {
        val mergedMap = mutableMapOf<String, JsonElement>()
        
        // Copy all default keys first, merging recursively if key exists in user
        for ((key, defaultValue) in default) {
            val userValue = user[key]
            if (userValue == null) {
                mergedMap[key] = defaultValue
            } else {
                mergedMap[key] = deepMerge(userValue, defaultValue)
            }
        }
        
        // Preserve any custom user keys that might not exist in default
        for ((key, userValue) in user) {
            if (!mergedMap.containsKey(key)) {
                mergedMap[key] = userValue
            }
        }
        
        return JsonObject(mergedMap)
    }
    // If either is not an object (e.g. primitives, arrays), preserve the user value
    return user
}
