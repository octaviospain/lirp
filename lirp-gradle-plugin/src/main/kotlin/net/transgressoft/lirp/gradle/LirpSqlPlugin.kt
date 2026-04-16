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

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that ensures `lirp-sql` is available on the KSP resolver's classpath when it is
 * declared as a project dependency, enabling automatic `SqlTableDef` code generation without
 * manual `ksp { arg(...) }` configuration.
 *
 * When this plugin detects `lirp-sql` in the project's `implementation`, `api`, `compileOnly`,
 * or `compileOnlyApi` dependencies, it adds `lirp-sql` to the `ksp` configuration as well.
 * This makes `SqlTableDef` visible to the KSP
 * resolver via `resolver.getClassDeclarationByName()`, which is the sole detection mechanism
 * in `TableDefProcessor`.
 *
 * Apply this plugin alongside the KSP plugin:
 * ```groovy
 * plugins {
 *     id 'com.google.devtools.ksp' version 'X.Y.Z'
 *     id 'net.transgressoft.lirp.sql' version 'X.Y.Z'
 * }
 * ```
 *
 * KSP2's processor classloader is isolated from `implementation` dependencies (see KSP
 * `KspAATask.kt` — processor runs in a dedicated `URLClassLoader` seeded only from the `ksp`
 * configuration). This plugin bridges the gap by mirroring `lirp-sql` into the `ksp`
 * configuration so the resolver can find `SqlTableDef`. Maven users must add `lirp-sql`
 * as a processor dependency in the KSP Maven plugin configuration manually.
 */
class LirpSqlPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.google.devtools.ksp") {
            // afterEvaluate is required to read declared dependencies after the build script
            // has been fully evaluated; pluginManager.withPlugin alone does not guarantee
            // that dependency declarations are complete.
            project.afterEvaluate {
                val candidateConfigs = listOf("implementation", "api", "compileOnly", "compileOnlyApi")
                val lirpSqlDep =
                    candidateConfigs
                        .asSequence()
                        .mapNotNull { project.configurations.findByName(it) }
                        .flatMap { it.dependencies.asSequence() }
                        .find { dep -> dep.group == "net.transgressoft" && dep.name == "lirp-sql" }
                if (lirpSqlDep != null) {
                    // Add lirp-sql to the ksp configuration so the KSP resolver can find
                    // SqlTableDef via resolver.getClassDeclarationByName(). This is the
                    // mechanism that replaced the removed options["lirp.sql"] KSP arg (D-04).
                    project.dependencies.add("ksp", lirpSqlDep)
                }
            }
        }
    }
}