/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Functional tests for [LirpSqlPlugin] using Gradle TestKit.
 *
 * Tests use a stub KSP plugin (created in buildSrc) that only registers the
 * `com.google.devtools.ksp` plugin ID and creates a `ksp` configuration.
 * This avoids depending on the real KSP Gradle plugin artifact which is not
 * available as a library dependency on public repositories.
 */
@DisplayName("LirpSqlPlugin")
internal class LirpSqlPluginTest : FunSpec({

    lateinit var projectDir: File

    val printKspDepsTask =
        """
        tasks.register('printKspDeps') {
            doLast {
                def kspConfig = project.configurations.findByName('ksp')
                println "KSP_DEPS: " + (kspConfig != null ? kspConfig.dependencies.collect { it.group + ':' + it.name } : 'no-ksp-config')
            }
        }
        """.trimIndent()

    fun File.createKspStubPlugin() {
        val buildSrc = resolve("buildSrc")
        buildSrc.resolve("src/main/java").mkdirs()
        buildSrc.resolve("build.gradle").writeText(
            """
            plugins { id 'java-gradle-plugin' }
            gradlePlugin {
                plugins {
                    kspStub {
                        id = 'com.google.devtools.ksp'
                        implementationClass = 'KspStubPlugin'
                    }
                }
            }
            """.trimIndent()
        )
        buildSrc.resolve("src/main/java/KspStubPlugin.java").writeText(
            """
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;

            public class KspStubPlugin implements Plugin<Project> {
                @Override
                public void apply(Project project) {
                    project.getConfigurations().maybeCreate("ksp");
                }
            }
            """.trimIndent()
        )
    }

    beforeEach {
        projectDir = createTempDirectory("lirp-plugin-test").toFile()
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'test-project'")
    }

    afterEach {
        projectDir.deleteRecursively()
    }

    test("adds lirp-sql to ksp configuration when lirp-sql is in implementation dependencies") {
        projectDir.createKspStubPlugin()
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'com.google.devtools.ksp'
                id 'net.transgressoft.lirp.sql'
            }
            configurations { implementation }
            dependencies {
                implementation 'net.transgressoft:lirp-sql:1.0.0'
            }
            $printKspDepsTask
            """.trimIndent()
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printKspDeps", "--stacktrace")
                .build()

        result.output shouldContain "net.transgressoft:lirp-sql"
    }

    test("adds lirp-sql to ksp configuration when lirp-sql is in api dependencies") {
        projectDir.createKspStubPlugin()
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.google.devtools.ksp'
                id 'net.transgressoft.lirp.sql'
            }
            dependencies {
                api 'net.transgressoft:lirp-sql:1.0.0'
            }
            $printKspDepsTask
            """.trimIndent()
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printKspDeps", "--stacktrace")
                .build()

        result.output shouldContain "net.transgressoft:lirp-sql"
    }

    test("does not add lirp-sql to ksp configuration when lirp-sql is absent from dependencies") {
        projectDir.createKspStubPlugin()
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'com.google.devtools.ksp'
                id 'net.transgressoft.lirp.sql'
            }
            $printKspDepsTask
            """.trimIndent()
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printKspDeps", "--stacktrace")
                .build()

        result.output shouldNotContain "net.transgressoft:lirp-sql"
    }

    test("does not fail when KSP plugin is not applied") {
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.transgressoft.lirp.sql'
            }
            """.trimIndent()
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("tasks", "--stacktrace")
                .build()

        result.task(":tasks")!!.outcome shouldBe TaskOutcome.SUCCESS
    }
})