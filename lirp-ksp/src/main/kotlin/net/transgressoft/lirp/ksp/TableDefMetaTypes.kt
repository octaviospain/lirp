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

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

/**
 * Resolved metadata for a single mapped column derived from a property on an entity or
 * `@Embeddable`. Carries everything the source-emitter needs to generate `ColumnDef` entries,
 * `fromRow`, `toParams`, `applyRow`, and `applyScalarRow` bodies without re-querying the KSP
 * model.
 */
internal data class ColumnMeta(
    val name: String,
    val propertyName: String,
    val typeExpression: String,
    val typeFqn: String,
    val nullable: Boolean,
    val isPrimaryKey: Boolean,
    val isEnum: Boolean = false,
    val isMutable: Boolean = false,
    val isCtorParam: Boolean = false,
    val isVersion: Boolean = false,
    val converterFqn: String? = null,
    val converterSqlFqn: String? = null,
    /**
     * Dot-separated entity-access path from the entity root down to the underlying scalar.
     * For top-level scalars this equals [propertyName]; for fields produced by flattening an
     * `@Embedded` value object this is the nested path (e.g. `album.performer.name`). Used by
     * `toParams` to dereference the value when binding the column.
     */
    val embeddedPath: String = propertyName,
    /**
     * `true` when this column was produced by flattening an `@Embedded` value object. Embedded-
     *  derived columns are excluded from the entity-level mutability gate and from `applyRow`
     *  because embeddables are reconstructed wholesale via the primary constructor on each
     *  `fromRow` call.
     */
    val isInsideEmbedded: Boolean = false,
    /**
     * `true` when this column was produced from an `@ElementCollection`-annotated property.
     * The column holds a JSON array (NOT NULL DEFAULT '[]'); `fromRow` decodes via
     * `Json.decodeFromString` and `toParams` encodes via `Json.encodeToString`.
     */
    val isElementCollection: Boolean = false,
    /**
     * FQN of the element converter object used to convert each element to/from its SQL scalar
     * representation. Non-null when [isElementCollection] is `true`.
     */
    val elementConverterFqn: String? = null,
    /**
     * The kind of collection backing the element-collection property: `"List"` or `"Set"`.
     * Non-null when [isElementCollection] is `true`. Drives terminal `.toSet()` emission in
     * `fromRow` for `Set`-typed properties.
     */
    val collectionKind: String? = null,
    /**
     * SQL DEFAULT expression for this column. When non-null the value is emitted as the
     * `defaultExpression` argument of the generated `ColumnDef(...)` literal, which the
     * runtime `ExposedTableInterpreter` translates into a `.default(...)` call on the column.
     * For element-collection columns this is `"[]"` (empty JSON array).
     */
    val defaultExpression: String? = null
)

/**
 * Resolved metadata for a single `@Aggregate`-annotated property, capturing the information
 * needed to emit junction-table descriptors (collection refs) and foreign-key constraints
 * (single-entity refs).
 */
internal data class AggregatePropertyMeta(
    val property: KSPropertyDeclaration,
    val propertyName: String,
    val isCollection: Boolean,
    val isOrdered: Boolean,
    val onDeleteName: String,
    val referencedClass: KSClassDeclaration,
    val backingScalarName: String?,
    val backingCollectionName: String? = null
)

/**
 * Resolved metadata for one collection-typed `@Aggregate` property, used to emit
 * `junctionTableDefs`, `junctionAccessors`, and `applyJunctionRows` on the parent's
 * `_LirpTableDef`.
 */
internal data class JunctionRefInfo(
    val propertyName: String,
    val backingFieldName: String,
    val junctionObjectName: String,
    val isOrdered: Boolean,
    val itemKeyTypeSimpleName: String,
    val isMutableCollection: Boolean
)

/**
 * Resolved metadata for a single FK constraint emitted by a single-entity `@Aggregate`
 * property. Carries the local column name, referenced table/column names, and the cascade
 * action string for the generated `ForeignKeyDef`.
 */
internal data class ForeignKeyMeta(
    val columnName: String,
    val referencedTable: String,
    val referencedColumn: String,
    val onDelete: String
)

/**
 * Bundle of `@PersistenceProperty` hint values consumed by the codegen path that derives
 * a column's `typeExpression`. Carrying them as a single value keeps refinement-helper
 * signatures focused.
 */
internal data class PersistencePropertyHints(
    val length: Int,
    val precision: Int,
    val scale: Int,
    val typeHint: String
)

/** Resolved converter class FQN and the SQL scalar type FQN for a `@PersistenceProperty(converter = …)` site. */
internal data class ConverterInfo(
    val converterFqn: String,
    val sqlTypeFqn: String
)

/**
 * Raw parsed metadata for one arm extracted from the source text of a `polymorphicAggregate(...)`
 * property declaration. Carries the textual K and E type names, the arm label, the backing scalar
 * identifier, and the cascade action name — all as strings at this stage; FQN resolution happens
 * in [TableDefProcessor.collectAggregateProperties] after import-list lookup.
 *
 * After FQN resolution, [entityFqn] is set to the fully-qualified name of the arm's target entity
 * class so the sealed-union emitter can generate exact-typed subtypes and import statements
 * without re-querying the KSP model.
 */
internal data class ArmTextMeta(
    val kTypeName: String,
    val eTypeName: String,
    val label: String,
    val scalarName: String,
    val onDeleteName: String = "DETACH",
    /** Fully-qualified name of the arm's target entity class; populated after import-list resolution. */
    val entityFqn: String = ""
)