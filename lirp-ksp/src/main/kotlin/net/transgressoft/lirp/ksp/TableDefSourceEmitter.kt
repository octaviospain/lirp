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
 * Aggregates the descriptor-shape inputs the table-def emitter consumes, keeping the
 * emitter API below Sonar's 7-parameter ceiling without inlining the structure at every
 * call site.
 */
internal data class ObjectBodyParams(
    val tableName: String,
    val selfType: String,
    val canGenerateSqlMapping: Boolean,
    val columns: List<ColumnMeta>,
    val constructorParamNames: List<String> = emptyList(),
    val ctorSlots: List<CtorSlot> = emptyList(),
    val setterSlots: List<EmbeddedSetterSlot> = emptyList(),
    val foreignKeys: List<ForeignKeyMeta> = emptyList(),
    val junctionRefs: List<JunctionRefInfo> = emptyList(),
    val isReactiveEntity: Boolean = true,
    val creatorCallExpression: String? = null,
    val creatorParamNames: List<String>? = null
)

/**
 * Pure source-text builder for `_LirpTableDef` objects. Accepts fully resolved [ColumnMeta],
 * [ForeignKeyMeta], and [JunctionRefInfo] lists and emits the corresponding Kotlin source via
 * `StringBuilder` extension functions. Carries no KSP model state — all inputs are plain data
 * so this class can be tested without a running KSP environment.
 */
internal object TableDefSourceEmitter {

    fun StringBuilder.appendPackageAndImports(
        packageName: String,
        canGenerateSqlMapping: Boolean,
        columns: List<ColumnMeta>,
        emitsForeignKeys: Boolean = false,
        emitsJunctions: Boolean = false,
        emitsVersioned: Boolean = false
    ) {
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName")
            appendLine()
        }
        appendLine("import net.transgressoft.lirp.persistence.ColumnDef")
        appendLine("import net.transgressoft.lirp.persistence.ColumnType")
        if (canGenerateSqlMapping) {
            appendLine("import net.transgressoft.lirp.persistence.LirpTableDef")
            appendLine("import net.transgressoft.lirp.persistence.sql.SqlTableDef")
            appendLine("import org.jetbrains.exposed.v1.core.Column")
            appendLine("import org.jetbrains.exposed.v1.core.ResultRow")
            appendLine("import org.jetbrains.exposed.v1.core.Table")
            if (emitsVersioned) {
                appendLine("import net.transgressoft.lirp.persistence.sql.VersionedTableDef")
            }
            if (emitsForeignKeys) {
                appendLine("import net.transgressoft.lirp.entity.CascadeAction")
                appendLine("import net.transgressoft.lirp.persistence.sql.ForeignKeyAware")
                appendLine("import net.transgressoft.lirp.persistence.sql.ForeignKeyDef")
            }
            if (emitsJunctions) {
                appendLine("import net.transgressoft.lirp.persistence.sql.JunctionAccessor")
                appendLine("import net.transgressoft.lirp.persistence.sql.JunctionAware")
                appendLine("import net.transgressoft.lirp.persistence.sql.JunctionTableDef")
            }
            appendConditionalTypeImports(columns)
        } else {
            appendLine("import net.transgressoft.lirp.persistence.LirpTableDef")
        }
        appendLine()
    }

    fun StringBuilder.appendConditionalTypeImports(columns: List<ColumnMeta>) {
        if (columns.any { it.typeFqn == UUID_FQN }) {
            appendLine("import kotlin.uuid.ExperimentalUuidApi")
            appendLine("import kotlin.uuid.toJavaUuid")
            appendLine("import kotlin.uuid.toKotlinUuid")
        }
        if (columns.any { it.typeFqn == LOCAL_DATE_FQN }) {
            appendLine("import kotlinx.datetime.toJavaLocalDate")
            appendLine("import kotlinx.datetime.toKotlinLocalDate")
        }
        if (columns.any { it.typeFqn == LOCAL_DATE_TIME_FQN }) {
            appendLine("import kotlinx.datetime.toJavaLocalDateTime")
            appendLine("import kotlinx.datetime.toKotlinLocalDateTime")
        }
        if (columns.any { it.typeExpression.startsWith("ColumnType.DecimalType") }) {
            appendLine("import java.math.BigDecimal")
        }
        columns.filter { it.isEnum }.map { it.typeFqn }.distinct().forEach { fqn ->
            appendLine("import $fqn")
        }
        if (columns.any { it.isElementCollection }) {
            appendLine("import kotlinx.serialization.json.Json")
            appendLine("import kotlinx.serialization.encodeToString")
            appendLine("import kotlinx.serialization.decodeFromString")
        }
    }

    fun StringBuilder.appendObjectBody(
        tableDefName: String,
        className: String,
        params: ObjectBodyParams,
        visibility: String = "public"
    ) {
        val tableName = params.tableName
        val selfType = params.selfType
        val canGenerateSqlMapping = params.canGenerateSqlMapping
        val columns = params.columns
        val foreignKeys = params.foreignKeys
        val junctionRefs = params.junctionRefs
        appendLine("/** KSP-generated table descriptor for [$className]. */")
        if (canGenerateSqlMapping && columns.any { it.typeFqn == UUID_FQN }) {
            appendLine("@OptIn(ExperimentalUuidApi::class)")
        }
        // Type parameter is the reactive self-type R so the descriptor satisfies the repository
        // bound `R : ReactiveEntity<K, R>`. Method bodies downcast to the concrete class via
        // `val entity = entityRef as $className` when R differs from the concrete class.
        val superType = resolveSuperType(params)
        appendLine("$visibility object $tableDefName : $superType {")
        appendLine("    override val tableName: String = \"$tableName\"")
        appendColumnsList(columns)
        if (canGenerateSqlMapping) {
            appendLine()
            appendFromRow(className, params)
            appendLine()
            appendToParams(className, selfType, columns)
            appendLine()
            appendApplyRow(className, selfType, columns)
            appendLine()
            appendApplyScalarRow(selfType, columns, params.setterSlots)
            appendBumpVersion(className, selfType, columns)
            appendForeignKeys(foreignKeys)
            appendJunctionOverrides(className, selfType, junctionRefs)
        }
        appendLine("}")
    }

    /**
     * Resolves the generated descriptor's supertype clause. SQL-mapping descriptors extend
     * `SqlTableDef<R>` plus any opt-in capability interfaces the entity needs (VersionedTableDef
     * for `@Version`, ForeignKeyAware for scalar FK refs, JunctionAware for collection refs);
     * non-SQL descriptors extend only `LirpTableDef<R>`.
     */
    private fun resolveSuperType(params: ObjectBodyParams): String {
        val selfType = params.selfType
        if (!params.canGenerateSqlMapping) return "LirpTableDef<$selfType>"
        val extras =
            buildList {
                if (params.columns.any { it.isVersion }) add("VersionedTableDef<$selfType>")
                if (params.foreignKeys.isNotEmpty()) add("ForeignKeyAware")
                if (params.junctionRefs.isNotEmpty()) add("JunctionAware<$selfType>")
            }
        return if (extras.isEmpty()) "SqlTableDef<$selfType>" else "SqlTableDef<$selfType>, ${extras.joinToString(", ")}"
    }

    /** Emits the `override val columns: List<ColumnDef> = listOf(...)` block for the descriptor. */
    private fun StringBuilder.appendColumnsList(columns: List<ColumnMeta>) {
        appendLine("    override val columns: List<ColumnDef> = listOf(")
        if (columns.isNotEmpty()) {
            val columnsCode =
                columns.joinToString(LIST_ITEM_SEPARATOR) { col ->
                    val base =
                        "ColumnDef(name = \"${col.name}\", type = ${col.typeExpression}, " +
                            "nullable = ${col.nullable}, primaryKey = ${col.isPrimaryKey}, isVersion = ${col.isVersion})"
                    if (col.defaultExpression != null) "${base.dropLast(1)}, defaultExpression = \"${col.defaultExpression}\")" else base
                }
            appendLine("        $columnsCode")
        }
        appendLine("    )")
    }

    fun StringBuilder.appendJunctionOverrides(
        className: String,
        selfType: String,
        junctionRefs: List<JunctionRefInfo>
    ) {
        if (junctionRefs.isEmpty()) return
        val receiver = receiverName(className, selfType)
        val idsOfAccess = if (className == selfType) "entity" else "(entityRef as $className)"
        appendLine()
        appendLine("    override val junctionTableDefs: List<JunctionTableDef> = listOf(")
        appendLine("        ${junctionRefs.joinToString(LIST_ITEM_SEPARATOR) { it.junctionObjectName }}")
        appendLine("    )")
        appendLine()
        appendLine("    override val junctionAccessors: List<JunctionAccessor<$selfType>> = listOf(")
        junctionRefs.forEachIndexed { idx, ref ->
            val trailingComma = if (idx == junctionRefs.lastIndex) "" else ","
            appendLine("        object : JunctionAccessor<$selfType> {")
            appendLine("            override val descriptor: JunctionTableDef = ${ref.junctionObjectName}")
            appendLine("            override fun idsOf($receiver: $selfType): Collection<Any> = $idsOfAccess.${ref.backingFieldName}")
            appendLine("        }$trailingComma")
        }
        appendLine("    )")
        appendLine()
        appendLine("    override fun applyJunctionRows(")
        appendLine("        $receiver: $selfType,")
        appendLine("        descriptor: JunctionTableDef,")
        appendLine("        ids: List<Any>,")
        appendLine("    ) {")
        if (className != selfType) appendCastToConcrete(className)
        appendLine("        entity.withEventsDisabled {")
        appendLine("            when (descriptor) {")
        for (ref in junctionRefs) {
            val rhs =
                if (ref.isOrdered) {
                    "ids.filterIsInstance<${ref.itemKeyTypeSimpleName}>()"
                } else {
                    "ids.filterIsInstance<${ref.itemKeyTypeSimpleName}>().toSet()"
                }
            appendLine("                ${ref.junctionObjectName} ->")
            appendLine("                    entity.${ref.backingFieldName} = $rhs")
        }
        appendLine("            }")
        appendLine(INNER_BLOCK_CLOSE)
        appendLine(METHOD_CLOSE)
    }

    fun StringBuilder.appendForeignKeys(foreignKeys: List<ForeignKeyMeta>) {
        if (foreignKeys.isEmpty()) return
        appendLine()
        appendLine("    override fun foreignKeys(): List<ForeignKeyDef> = listOf(")
        val entries =
            foreignKeys.joinToString(LIST_ITEM_SEPARATOR) { fk ->
                "ForeignKeyDef(columnName = \"${fk.columnName}\", " +
                    "referencedTable = \"${fk.referencedTable}\", " +
                    "referencedColumn = \"${fk.referencedColumn}\", " +
                    "onDelete = CascadeAction.${fk.onDelete})"
            }
        appendLine("        $entries")
        appendLine("    )")
    }

    /**
     * Emits a body-opening downcast line when the descriptor is typed on a distinct reactive
     * self-type R. Aliases the `entityRef: R` parameter to a local `entity` typed on the concrete
     * class so all downstream body lines remain unchanged whether R equals the class or not.
     */
    private fun StringBuilder.appendCastToConcrete(className: String) {
        appendLine("        val entity = entityRef as $className")
    }

    /**
     * Parameter name for self-type-parameterized overrides: `entityRef` when a downcast alias is
     * emitted (R differs from the concrete class), else `entity` for self-referential entities.
     */
    private fun receiverName(className: String, selfType: String): String = if (className != selfType) "entityRef" else "entity"

    fun StringBuilder.appendFromRow(className: String, params: ObjectBodyParams) {
        val selfType = params.selfType
        val columns = params.columns
        val constructorParamNames = params.constructorParamNames
        val ctorSlots = params.ctorSlots
        val embeddedSetterSlots = params.setterSlots
        val isReactiveEntity = params.isReactiveEntity
        val creatorCallExpression = params.creatorCallExpression
        val creatorParamNames = params.creatorParamNames

        val columnsByName = columns.associateBy { it.propertyName }
        // When a creator is present, its param list may be a subset of the primary-ctor params;
        // use it to drive the flat column-lookup order so unlisted params are omitted.
        val orderedCtorCols = (creatorParamNames ?: constructorParamNames).mapNotNull { columnsByName[it] }
        val ctorParamNameSet = constructorParamNames.toSet()
        // Setter cols exclude both ctor params and embedded-derived columns (the latter share their
        // top-level entity ctor-param name and are reconstructed inside the ctor invocation).
        val setterCols = columns.filter { it.propertyName !in ctorParamNameSet && !it.isInsideEmbedded }

        // Return type is the self-type R; the body constructs the concrete class (a subtype of R).
        appendLine("    override fun fromRow(row: ResultRow, table: Table): $selfType {")
        appendLine(
            "        val entity = ${
                buildEntityConstruction(className, creatorCallExpression, constructorParamNames, creatorParamNames, ctorSlots, orderedCtorCols)
            }"
        )

        // Body-declared (non-ctor) reactive properties are assigned with events disabled: emitting
        // during hydration would schedule a stray write-back that races the repository's mutation
        // subscription. Non-reactive @PersistenceMapping classes lack withEventsDisabled, so they
        // assign directly.
        val wrapInEventsDisabled = isReactiveEntity && (setterCols.isNotEmpty() || embeddedSetterSlots.isNotEmpty())
        val setterIndent = if (wrapInEventsDisabled) "            " else "        "
        if (wrapInEventsDisabled) appendLine("        entity.withEventsDisabled {")
        appendSetterAssignments(setterCols, embeddedSetterSlots, setterIndent)
        if (wrapInEventsDisabled) appendLine(INNER_BLOCK_CLOSE)
        appendLine("        return entity")
        appendLine(METHOD_CLOSE)
    }

    /**
     * Builds the entity-construction expression for `fromRow`.
     *
     * When a `@PersistenceCreator` is present ([creatorCallExpression] non-null), the creator may
     * take a subset of the entity's parameters in a different order, so arguments are emitted as
     * **named** arguments in the creator's own parameter order ([creatorParamNames]); binding
     * positionally to the primary constructor would misbind or fail to compile. Without a creator,
     * the primary constructor is called positionally — using the structured [ctorSlots] tree to
     * emit nested constructor expressions for `@Embedded` parameters, or falling back to flat
     * column-by-name lookup ([orderedCtorCols]) for the common no-embedded case.
     */
    private fun buildEntityConstruction(
        className: String,
        creatorCallExpression: String?,
        constructorParamNames: List<String>,
        creatorParamNames: List<String>?,
        ctorSlots: List<CtorSlot>,
        orderedCtorCols: List<ColumnMeta>
    ): String {
        val callTarget = creatorCallExpression ?: className
        val ctorArgs =
            when {
                creatorCallExpression != null -> {
                    val slotByName = ctorSlots.associateBy { it.ctorParamName }
                    val colByName = orderedCtorCols.associateBy { it.propertyName }
                    (creatorParamNames ?: constructorParamNames).joinToString(", ") { name ->
                        val arg =
                            slotByName[name]?.let { buildCtorArgExpression(it) }
                                ?: colByName[name]?.let { buildRowAccess(it) }
                                ?: error("No slot or column source for @PersistenceCreator parameter '$name'")
                        "$name = $arg"
                    }
                }
                ctorSlots.isNotEmpty() -> ctorSlots.joinToString(", ") { buildCtorArgExpression(it) }
                else -> orderedCtorCols.joinToString(", ") { buildRowAccess(it) }
            }
        return "$callTarget($ctorArgs)"
    }

    /**
     * Emits the post-construction reactive-property assignments inside `fromRow`: flat setter
     * columns and the reconstruction of each body-declared `@Embedded var`. Body-declared
     * `@Embedded var` leaves are `isInsideEmbedded`, so they are excluded from [setterCols] and
     * rebuilt here from their nested constructor expression (routing through the creator when
     * present) — this hydrates the var on a standalone `fromRow()` (e.g. conflict-recovery reload)
     * rather than leaving it at its default.
     */
    private fun StringBuilder.appendSetterAssignments(
        setterCols: List<ColumnMeta>,
        embeddedSetterSlots: List<EmbeddedSetterSlot>,
        indent: String
    ) {
        for (col in setterCols) {
            appendLine("${indent}entity.${col.propertyName} = ${buildRowAccess(col)}")
        }
        for (slot in embeddedSetterSlots) {
            val reconstruction =
                buildCtorArgExpression(EmbeddedCtorSlot(slot.ctorParamName, slot.embeddableTypeFqn, slot.children, slot.creatorCallExpression))
            appendLine("${indent}entity.${slot.ctorParamName} = $reconstruction")
        }
    }

    fun StringBuilder.appendToParams(className: String, selfType: String, columns: List<ColumnMeta>) {
        appendLine("    override fun toParams(${receiverName(className, selfType)}: $selfType, table: Table): Map<Column<*>, Any?> {")
        if (className != selfType) appendCastToConcrete(className)
        appendLine("        val cols = table.columns.associateBy { it.name }")
        appendLine("        return mapOf(")
        val paramEntries =
            columns.joinToString(",\n            ") { col ->
                val valueAccess = buildEntityAccess(col)
                "cols[\"${col.name}\"]!! to $valueAccess"
            }
        appendLine("            $paramEntries")
        appendLine("        )")
        appendLine(METHOD_CLOSE)
    }

    fun StringBuilder.appendApplyRow(className: String, selfType: String, columns: List<ColumnMeta>) {
        // applyRow overwrites the state of an existing entity — skip primary-key columns
        // (they are immutable post-construction), any non-mutable property, and any column
        // produced by flattening an @Embedded value object (embeddables are reconstructed
        // wholesale via the primary constructor in fromRow; the parent entity has no
        // addressable scalar setter for them).
        val mutableNonPk = columns.filter { !it.isPrimaryKey && it.isMutable && !it.isInsideEmbedded }
        appendLine("    override fun applyRow(${receiverName(className, selfType)}: $selfType, row: ResultRow, table: Table) {")
        if (mutableNonPk.isEmpty()) {
            appendLine("        // No mutable non-PK columns — applyRow is a no-op.")
        } else {
            if (className != selfType) appendCastToConcrete(className)
            for (col in mutableNonPk) {
                val rowAccess = buildRowAccess(col)
                appendLine("        entity.${col.propertyName} = $rowAccess")
            }
        }
        appendLine(METHOD_CLOSE)
    }

    fun StringBuilder.appendApplyScalarRow(
        selfType: String,
        columnsIn: List<ColumnMeta>,
        embeddedSetterSlots: List<EmbeddedSetterSlot> = emptyList()
    ) {
        // Override the default applyScalarRow on SqlTableDef. The default body throws — the override
        // walks the supplied LirpRawInitializer entries, resolves each entry's Kotlin property name
        // to its column on the table, reads the row value with the same conversion semantics as
        // fromRow / applyRow (UUID, LocalDate, LocalDateTime, Enum), and dispatches to the entry's
        // silentSetter so reactive backing fields are written without firing events.
        // Skip @Embedded-derived columns: they share their top-level entity ctor-param name, which
        // would emit duplicate `when` branches; rawInit never carries entries for embedded scalars.
        // Typed on the self-type R — no concrete downcast needed: the body only forwards `entity`
        // to `entry.silentSetter`, which is also typed on R via LirpRawInitializer<R>.
        val columns = columnsIn.filterNot { it.isInsideEmbedded }
        appendLine("    override fun applyScalarRow(")
        appendLine("        entity: $selfType,")
        appendLine("        row: org.jetbrains.exposed.v1.core.ResultRow,")
        appendLine("        table: org.jetbrains.exposed.v1.core.Table,")
        appendLine("        rawInit: net.transgressoft.lirp.persistence.LirpRawInitializer<$selfType>")
        appendLine("    ) {")
        if (columns.isEmpty() && embeddedSetterSlots.isEmpty()) {
            appendLine("        // No mapped columns — applyScalarRow is a no-op.")
        } else {
            appendLine("        for (entry in rawInit.entries) {")
            appendLine("            val value: Any? = when (entry.name) {")
            for (col in columns) {
                val rowAccess = buildRowAccess(col)
                appendLine("                \"${col.propertyName}\" -> $rowAccess")
            }
            // One branch per body-declared @Embedded setter slot. The key is the top-level
            // property name (the RawInitializer entry name); the value is the full nested
            // constructor expression reconstructed via buildCtorArgExpression.
            for (slot in embeddedSetterSlots) {
                val reconstruction =
                    buildCtorArgExpression(EmbeddedCtorSlot(slot.ctorParamName, slot.embeddableTypeFqn, slot.children, slot.creatorCallExpression))
                appendLine("                \"${slot.ctorParamName}\" -> $reconstruction")
            }
            appendLine("                else -> continue")
            appendLine("            }")
            appendLine("            entry.silentSetter(entity, value)")
            appendLine(INNER_BLOCK_CLOSE)
        }
        appendLine(METHOD_CLOSE)
    }

    fun StringBuilder.appendBumpVersion(className: String, selfType: String, columns: List<ColumnMeta>) {
        // Emit a non-default bumpVersion override only when the entity declares a @Version
        // column. Unversioned entities inherit the interface no-op default, so no emission keeps
        // the generated file minimal.
        val versionCol = columns.singleOrNull { it.isVersion } ?: return
        appendLine()
        appendLine("    override fun bumpVersion(${receiverName(className, selfType)}: $selfType, newVersion: Long) {")
        if (className != selfType) appendCastToConcrete(className)
        appendLine("        entity.${versionCol.propertyName} = newVersion")
        appendLine(METHOD_CLOSE)
    }

    /**
     * Recursively emits a Kotlin expression that reconstructs the value bound to [slot] from the
     * current `ResultRow`. Scalar slots delegate to [buildRowAccess]; embedded slots emit a nested
     * constructor invocation whose arguments are themselves recursive expressions.
     */
    fun buildCtorArgExpression(slot: CtorSlot): String =
        when (slot) {
            is ScalarCtorSlot -> buildRowAccess(slot.column)
            is EmbeddedCtorSlot -> {
                val callTarget = slot.creatorCallExpression ?: slot.embeddableTypeFqn
                val inner =
                    slot.children
                        .filterNot { it is OmittedCtorSlot }
                        .joinToString(", ") { child ->
                            "${child.ctorParamName} = ${buildCtorArgExpression(child)}"
                        }
                "$callTarget($inner)"
            }
            // @PersistenceIgnore on a nullable @Embeddable constructor param: emit null so the
            // ignored param is excluded from SQL column mapping while the constructor still compiles.
            is IgnoredCtorSlot -> "null"
            // OmittedCtorSlot must be filtered from EmbeddedCtorSlot.children before dispatch so
            // the default applies at instantiation time; reaching here is an invariant violation.
            is OmittedCtorSlot -> error("OmittedCtorSlot must be filtered before buildCtorArgExpression dispatch")
            // EmbeddedSetterSlot is consumed directly by appendApplyScalarRow via a synthetic
            // EmbeddedCtorSlot wrapper; it must never reach this dispatch path.
            is EmbeddedSetterSlot -> error("EmbeddedSetterSlot must not be passed to buildCtorArgExpression directly")
        }

    fun buildRowAccess(col: ColumnMeta): String {
        val rawAccess = "row[table.columns.first { it.name == \"${col.name}\" }]"
        return buildElementCollectionRowAccess(col, rawAccess)
            ?: buildConverterRowAccess(col, rawAccess)
            ?: buildNarrowingIntRowAccess(col, rawAccess)
            ?: buildBuiltInRowAccess(col, rawAccess)
    }

    /**
     * Emits the read-side access expression for an `@ElementCollection` column. The column is a
     * NOT NULL TEXT holding a JSON array. The expression decodes the array via the
     * `kotlinx.serialization` Default [Json] instance (accessed by FQN so no import is required
     * at the call site), maps each decoded element-S value through the element converter's
     * `fromSql`, and — for `Set<E>` properties — appends a terminal `.toSet()`. The `S` FQN is
     * the converter's resolved SQL type stored in [ColumnMeta.converterSqlFqn]; the terminal call
     * is conditional on [ColumnMeta.collectionKind].
     *
     * Returns `null` when [col] is not an element-collection column, allowing the caller's `?:`
     * chain to fall through to [buildConverterRowAccess] and the built-in paths.
     */
    fun buildElementCollectionRowAccess(col: ColumnMeta, rawAccess: String): String? {
        if (!col.isElementCollection) return null
        val converter = col.elementConverterFqn!!
        val sFqn = col.converterSqlFqn!!
        val terminal = if (col.collectionKind == "Set") ".toSet()" else ""
        return "kotlinx.serialization.json.Json.decodeFromString<kotlin.collections.List<$sFqn>>(" +
            "$rawAccess as kotlin.String).map { $converter.fromSql(it) }$terminal"
    }

    // Converter-routed columns short-circuit the FQN-driven cast table: read the raw scalar,
    // cast to the converter's S type, then route through the consumer's fromSql. Short/Byte
    // converters need the same narrowing conversion that non-converter columns get. Casting
    // to Number rather than Int tolerates JDBC drivers that box integral values as Long or
    // Short instead of Int, avoiding ClassCastException at row time.
    fun buildConverterRowAccess(col: ColumnMeta, rawAccess: String): String? {
        if (col.converterFqn == null || col.converterSqlFqn == null) return null
        val converterInput =
            when (col.converterSqlFqn) {
                KOTLIN_SHORT_FQN ->
                    if (col.nullable) "($rawAccess as? Number)?.toShort()" else "($rawAccess as Number).toShort()"
                KOTLIN_BYTE_FQN ->
                    if (col.nullable) "($rawAccess as? Number)?.toByte()" else "($rawAccess as Number).toByte()"
                else ->
                    if (col.nullable) "($rawAccess as? ${col.converterSqlFqn})" else "($rawAccess as ${col.converterSqlFqn})"
            }
        return if (col.nullable) {
            "$converterInput?.let { ${col.converterFqn}.fromSql(it) }"
        } else {
            "${col.converterFqn}.fromSql($converterInput)"
        }
    }

    // Short / Byte are stored as INT; narrow on read via Kotlin's truncating conversion. Casting
    // to Number rather than Int tolerates JDBC drivers that box integral values as Long or Short
    // instead of Int, so .toShort()/.toByte() works regardless of the boxed numeric type returned.
    fun buildNarrowingIntRowAccess(col: ColumnMeta, rawAccess: String): String? =
        when (col.typeFqn) {
            KOTLIN_SHORT_FQN ->
                if (col.nullable) "($rawAccess as? Number)?.toShort()" else "($rawAccess as Number).toShort()"
            KOTLIN_BYTE_FQN ->
                if (col.nullable) "($rawAccess as? Number)?.toByte()" else "($rawAccess as Number).toByte()"
            else -> null
        }

    fun buildBuiltInRowAccess(col: ColumnMeta, rawAccess: String): String =
        when {
            col.typeFqn == UUID_FQN && col.nullable -> "($rawAccess as? kotlin.uuid.Uuid)?.toJavaUuid()"
            col.typeFqn == UUID_FQN -> "($rawAccess as kotlin.uuid.Uuid).toJavaUuid()"
            col.typeFqn == LOCAL_DATE_FQN && col.nullable -> "($rawAccess as? kotlinx.datetime.LocalDate)?.toJavaLocalDate()"
            col.typeFqn == LOCAL_DATE_FQN -> "($rawAccess as kotlinx.datetime.LocalDate).toJavaLocalDate()"
            col.typeFqn == LOCAL_DATE_TIME_FQN && col.nullable -> "($rawAccess as? kotlinx.datetime.LocalDateTime)?.toJavaLocalDateTime()"
            col.typeFqn == LOCAL_DATE_TIME_FQN -> "($rawAccess as kotlinx.datetime.LocalDateTime).toJavaLocalDateTime()"
            col.isEnum && col.nullable -> {
                val enumSimpleName = col.typeFqn.substringAfterLast(".")
                "($rawAccess as? String)?.let { enumValueOf<$enumSimpleName>(it) }"
            }
            col.isEnum -> {
                val enumSimpleName = col.typeFqn.substringAfterLast(".")
                "enumValueOf<$enumSimpleName>($rawAccess as String)"
            }
            col.nullable -> "$rawAccess as? ${col.typeFqn.substringAfterLast(".")}"
            else -> "$rawAccess as ${col.typeFqn.substringAfterLast(".")}"
        }

    fun buildEntityAccess(col: ColumnMeta): String {
        // Use embeddedPath so @Embedded-derived columns dereference the nested access path
        // (e.g. "entity.album.performer.name") rather than the top-level entity ctor-param name.
        // For non-embedded columns embeddedPath defaults to propertyName so behaviour is unchanged.
        val prop = "entity.${col.embeddedPath}"
        return elementCollectionEntityAccess(col, prop)
            ?: converterEntityAccess(col, prop)
            ?: builtInEntityAccess(col, prop)
    }

    /**
     * Emits the write-side access expression for an `@ElementCollection` column. Maps each domain
     * element through the element converter's `toSql` and encodes the resulting `List<S>` to a JSON
     * array string via the `kotlinx.serialization` Default [Json] instance (accessed by FQN). The
     * resolved element-S FQN ([ColumnMeta.converterSqlFqn]) drives the reified type parameter so
     * numeric element types (e.g. `kotlin.Int`) encode as JSON numbers rather than strings.
     *
     * Returns `null` when [col] is not an element-collection column, allowing the caller's `?:`
     * chain to fall through to [converterEntityAccess] and [builtInEntityAccess].
     */
    fun elementCollectionEntityAccess(col: ColumnMeta, prop: String): String? {
        if (!col.isElementCollection) return null
        val converter = col.elementConverterFqn!!
        val sFqn = col.converterSqlFqn!!
        return "kotlinx.serialization.json.Json.encodeToString<kotlin.collections.List<$sFqn>>(" +
            "$prop.map { $converter.toSql(it) })"
    }

    /**
     * Emits the write-side access expression for a converter-routed column. Routes the domain
     * value through the converter's `toSql`, then widens Short/Byte to Int to match the IntType
     * column the JDBC layer expects (symmetric to [buildConverterRowAccess]).
     *
     * Returns `null` when no converter is declared on [col].
     */
    fun converterEntityAccess(col: ColumnMeta, prop: String): String? {
        if (col.converterFqn == null) return null
        val sqlValue =
            if (col.nullable) "$prop?.let { ${col.converterFqn}.toSql(it) }"
            else "${col.converterFqn}.toSql($prop)"
        return when (col.converterSqlFqn) {
            KOTLIN_SHORT_FQN, KOTLIN_BYTE_FQN ->
                if (col.nullable) "$sqlValue?.toInt()" else "$sqlValue.toInt()"
            else -> sqlValue
        }
    }

    /**
     * Emits the write-side access expression for a column without a converter. Short/Byte widen
     * to Int; UUID, LocalDate, LocalDateTime, and enum columns apply the corresponding
     * Kotlin/kotlinx-datetime conversion. Plain scalars return the property expression verbatim.
     */
    fun builtInEntityAccess(col: ColumnMeta, prop: String): String =
        when {
            col.typeFqn == KOTLIN_SHORT_FQN && col.nullable -> "$prop?.toInt()"
            col.typeFqn == KOTLIN_SHORT_FQN -> "$prop.toInt()"
            col.typeFqn == KOTLIN_BYTE_FQN && col.nullable -> "$prop?.toInt()"
            col.typeFqn == KOTLIN_BYTE_FQN -> "$prop.toInt()"
            col.typeFqn == UUID_FQN && col.nullable -> "$prop?.toKotlinUuid()"
            col.typeFqn == UUID_FQN -> "$prop.toKotlinUuid()"
            col.typeFqn == LOCAL_DATE_FQN && col.nullable -> "$prop?.toKotlinLocalDate()"
            col.typeFqn == LOCAL_DATE_FQN -> "$prop.toKotlinLocalDate()"
            col.typeFqn == LOCAL_DATE_TIME_FQN && col.nullable -> "$prop?.toKotlinLocalDateTime()"
            col.typeFqn == LOCAL_DATE_TIME_FQN -> "$prop.toKotlinLocalDateTime()"
            col.isEnum && col.nullable -> "$prop?.name"
            col.isEnum -> "$prop.name"
            else -> prop
        }
}