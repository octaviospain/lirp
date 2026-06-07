/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.persistence

/**
 * Designates the factory the KSP-generated `fromRow` calls when reconstructing an entity or
 * `@Embeddable` value object from a database row, instead of invoking the primary constructor
 * directly.
 *
 * The annotated target must be a companion-object function or a secondary constructor of the
 * class. Top-level package functions are not supported. Its parameters must be a name-subset of
 * the primary constructor's parameters so the existing `CtorSlot` tree can supply each argument
 * by name. Parameters absent from the primary constructor's slot tree are an error unless they
 * carry a default value, in which case they are omitted from the generated named-argument call
 * and the default applies at instantiation time.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file and
 * read by the KSP processor at compile time. Runtime reflection does not need to see it, so
 * runtime retention is unnecessary.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * this annotation has no effect and `fromRow` will continue to call the primary constructor.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class PersistenceCreator