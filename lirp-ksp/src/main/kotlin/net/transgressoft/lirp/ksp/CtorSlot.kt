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

package net.transgressoft.lirp.ksp

/**
 * Captures the shape of an entity's (or nested `@Embeddable`'s) primary constructor for the
 * purposes of `fromRow` reconstruction. The KSP codegen produces one `CtorSlot` per primary-
 * constructor parameter, preserving declaration order, so the generated `fromRow` can emit
 * positional arguments in the right places.
 *
 * Scalar parameters resolve to a single column ([ScalarCtorSlot]); `@Embedded` parameters
 * resolve to a nested constructor expression whose own parameters are themselves slots
 * ([EmbeddedCtorSlot]). The recursion supports arbitrary nesting depth — the codegen walks
 * the tree to emit `TargetType(child1Expr, child2Expr, ...)` recursively for embedded slots.
 *
 * Internal to the `lirp-ksp` module; not exposed via `lirp-sql-api`.
 */
internal sealed interface CtorSlot {
    /** Name of the constructor parameter this slot fills. Used as the named-argument label
     *  in generated `fromRow` expressions for clarity at recursive call sites. */
    val ctorParamName: String
}

/**
 * A primary-constructor parameter backed by exactly one flattened column. Carries the resolved
 * [ColumnMeta] (including a `ColumnConverter` FQN when a Phase 56 converter is bound). Used both
 * for top-level entity scalars and for scalar leaves inside an `@Embedded` value object.
 */
internal data class ScalarCtorSlot(
    override val ctorParamName: String,
    val column: ColumnMeta
) : CtorSlot

/**
 * A primary-constructor parameter whose value is an `@Embeddable` instance reconstructed from
 * flattened columns. The [embeddableTypeFqn] names the embeddable's fully-qualified class so
 * `fromRow` can emit a constructor invocation; [children] holds the nested slots in declaration
 * order — they may themselves be [EmbeddedCtorSlot]s for recursive nesting.
 */
internal data class EmbeddedCtorSlot(
    override val ctorParamName: String,
    val embeddableTypeFqn: String,
    val children: List<CtorSlot>
) : CtorSlot