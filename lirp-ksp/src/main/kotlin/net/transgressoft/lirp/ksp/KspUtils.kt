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

package net.transgressoft.lirp.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * Returns the JVM binary simple name of this class declaration relative to its enclosing package.
 *
 * For top-level classes returns [simpleName]. For inner/nested classes, walks [parentDeclaration]
 * recursively joining with `$` to produce the binary name that [Class.forName] expects
 * (e.g., `Outer$Inner` for one level, `Outer$Middle$Inner` for two levels).
 */
internal fun KSClassDeclaration.jvmBinaryName(): String {
    val parent = parentDeclaration as? KSClassDeclaration
    return if (parent != null) {
        "${parent.jvmBinaryName()}\$${simpleName.asString()}"
    } else {
        simpleName.asString()
    }
}

/**
 * Returns the Kotlin nested class name relative to the enclosing package, using `.` as separator.
 *
 * For top-level classes returns [simpleName]. For inner/nested classes, walks [parentDeclaration]
 * recursively joining with `.` to produce the Kotlin-source-level name used for type references
 * within the same package (e.g., `Outer.Inner` for one level, `Outer.Middle.Inner` for two levels).
 */
internal fun KSClassDeclaration.kotlinNestedName(): String {
    val parent = parentDeclaration as? KSClassDeclaration
    return if (parent != null) {
        "${parent.kotlinNestedName()}.${simpleName.asString()}"
    } else {
        simpleName.asString()
    }
}