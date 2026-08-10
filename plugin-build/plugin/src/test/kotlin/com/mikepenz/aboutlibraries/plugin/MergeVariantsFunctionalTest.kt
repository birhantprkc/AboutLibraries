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
    fun `root and resolved variant are both collected when merging is disabled`() {
        val json = runExport(mergeVariants = false)

        // `duplicationMode = KEEP` (set by runExport) keeps both entries visible; with the default
        // MERGE they are collapsed onto whichever of the two the graph walk happened to reach first
        assertTrue(json.contains("\"androidx.collection:collection-jvm\""), "Expected resolved variant id. Output:\n$json")
        assertTrue(json.contains("\"androidx.collection:collection\""), "Expected redirect shell id. Output:\n$json")
    }

    @Test
    fun `variants are merged regardless of the order the graph is walked in`() {
        // `annotation-jvm` is declared first, so it is reached directly before the `annotation`
        // redirect shell that `collection` pulls in transitively. The merge must not depend on
        // which path reaches the platform artifact first.
        val json = runExport(
            mergeVariants = true,
            dependencies = listOf("androidx.annotation:annotation-jvm:1.9.1", "androidx.collection:collection:1.5.0"),
        )

        assertTrue(json.contains("\"androidx.annotation:annotation\""), "Expected declared root id. Output:\n$json")
        assertFalse(json.contains("\"androidx.annotation:annotation-jvm\""), "Resolved variant id must be replaced. Output:\n$json")
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
    fun `non-multiplatform dependency trees are byte-identical with and without merging`() {
        // guards against the detection widening beyond `available-at` redirects: a plain JVM tree
        // has no redirect shells at all, so enabling the option must be a no-op
        val dependencies = listOf("com.google.code.gson:gson:2.11.0", "org.slf4j:slf4j-api:2.0.16")

        val disabled = runExport(mergeVariants = false, dependencies = dependencies)
        val enabled = runExport(mergeVariants = true, dependencies = dependencies)

        assertEquals(disabled, enabled, "Merging must not alter output for non-KMP dependencies")
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

        @Suppress("WithPluginClasspathUsage")
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
