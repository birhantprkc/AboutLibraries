package com.mikepenz.aboutlibraries.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Verifies `aboutLibraries.library.mergeVariants`: Kotlin Multiplatform artifacts reached via a
 * Gradle `available-at` redirect are reported under the root coordinate that was declared, while
 * artifacts genuinely published under a suffixed coordinate stay untouched.
 *
 * `androidx.collection:collection` is a KMP publication: on a JVM classpath it redirects to
 * `androidx.collection:collection-jvm`.
 */
class MergeVariantsFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `root and resolved variant are both reported by default`() {
        val json = runExport(mergeVariants = false)

        // unchanged legacy behaviour: the redirect shell and the platform artifact are separate entries
        assertTrue(json.contains("\"androidx.collection:collection-jvm\""), "Expected resolved variant id. Output:\n$json")
        assertTrue(json.contains("\"androidx.collection:collection\""), "Expected redirect shell id. Output:\n$json")
    }

    @Test
    fun `variants are reported under the declared root coordinate when merging`() {
        val json = runExport(mergeVariants = true)

        assertTrue(json.contains("\"androidx.collection:collection\""), "Expected declared root id. Output:\n$json")
        assertFalse(json.contains("\"androidx.collection:collection-jvm\""), "Resolved variant id must be replaced")
        // metadata still comes from the resolved variant's POM
        assertTrue(json.contains("Standalone efficient collections."), "Description of the resolved variant should be kept")
    }

    @Test
    fun `suffixed coordinates without a redirect are untouched when merging`() {
        // no KMP root module in the graph, so there is nothing to merge into — the `-jvm` suffix
        // must not be stripped by name
        val json = runExport(mergeVariants = true, dependencies = listOf("androidx.annotation:annotation-jvm:1.9.1"))

        assertTrue(json.contains("\"androidx.annotation:annotation-jvm\""), "Suffixed id without redirect must be kept. Output:\n$json")
        assertFalse(json.contains("\"androidx.annotation:annotation\""), "Nothing should have been merged. Output:\n$json")
    }

    private fun runExport(
        mergeVariants: Boolean,
        dependencies: List<String> = listOf("androidx.collection:collection:1.5.0", "androidx.annotation:annotation-jvm:1.9.1"),
    ): String {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("java-library")
                id("com.mikepenz.aboutlibraries.plugin")
            }

            repositories {
                mavenCentral()
                google()
            }

            dependencies {
                ${dependencies.joinToString("\n                ") { "implementation(\"$it\")" }}
            }

            aboutLibraries {
                offlineMode = true
                library {
                    mergeVariants = $mergeVariants
                    duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.KEEP
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("exportLibraryDefinitions", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportLibraryDefinitions")?.outcome)

        val outputFile = File(projectDir, "build/generated/aboutLibraries/aboutlibraries.json")
        assertTrue(outputFile.exists(), "Output file should be created")
        return outputFile.readText()
    }
}
