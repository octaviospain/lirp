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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Aggregate
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.CollectionRefEntry
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpRefAccessor
import net.transgressoft.lirp.persistence.LirpRegistryInfo
import net.transgressoft.lirp.persistence.RefEntry
import net.transgressoft.lirp.persistence.fx.fxAggregateList
import net.transgressoft.lirp.persistence.fx.fxBoolean
import net.transgressoft.lirp.persistence.fx.fxDouble
import net.transgressoft.lirp.persistence.fx.fxInteger
import net.transgressoft.lirp.persistence.fx.fxObject
import net.transgressoft.lirp.persistence.fx.fxString
import javafx.beans.property.BooleanProperty
import javafx.beans.property.DoubleProperty
import javafx.beans.property.IntegerProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.StringProperty
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Minimal referenced entity for [FxSqlTestEntity]'s aggregate delegate.
 */
class FxSqlTestItem(override val id: Int, title: String) : ReactiveEntityBase<Int, FxSqlTestItem>() {
    var title: String by reactiveProperty(title)
    override val uniqueId: String get() = "fx-sql-item-$id"

    override fun clone(): FxSqlTestItem = FxSqlTestItem(id, title)
}

/** SQL table definition for [FxSqlTestItem]. */
object FxSqlTestItemTableDef : SqlTableDef<FxSqlTestItem> {
    override val tableName = "fx_sql_test_items"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("title", ColumnType.VarcharType(200), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): FxSqlTestItem {
        val cols = table.columns.associateBy { it.name }
        return FxSqlTestItem(
            row[cols["id"]!! as Column<Int>],
            row[cols["title"]!! as Column<String>]
        )
    }

    override fun toParams(entity: FxSqlTestItem, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["title"]!! to entity.title
        )
    }
}

/**
 * Test entity combining fx scalar delegates ([fxString], [fxInteger], [fxBoolean], [fxDouble],
 * [fxObject]) with an [fxAggregateList] collection delegate for SQL persistence testing.
 */
class FxSqlTestEntity(
    override val id: Int,
    initialName: String = "",
    initialYear: Int = 0,
    initialActive: Boolean = false,
    initialRating: Double = 0.0,
    initialTag: String? = null,
    initialItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, FxSqlTestEntity>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "fx-sql-test-$id"

    val nameProperty: StringProperty by fxString(initialName, dispatchToFxThread = false)
    val yearProperty: IntegerProperty by fxInteger(initialYear, dispatchToFxThread = false)
    val activeProperty: BooleanProperty by fxBoolean(initialActive, dispatchToFxThread = false)
    val ratingProperty: DoubleProperty by fxDouble(initialRating, dispatchToFxThread = false)
    val tagProperty: ObjectProperty<String?> by fxObject<String?>(initialTag, dispatchToFxThread = false)

    @Aggregate(onDelete = CascadeAction.NONE)
    val items by fxAggregateList<Int, FxSqlTestItem>(initialItemIds, dispatchToFxThread = false)

    override fun clone(): FxSqlTestEntity =
        FxSqlTestEntity(
            id, nameProperty.get(), yearProperty.get(), activeProperty.get(),
            ratingProperty.get(), tagProperty.get(), items.referenceIds.toList()
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FxSqlTestEntity) return false
        return id == other.id &&
            nameProperty.get() == other.nameProperty.get() &&
            yearProperty.get() == other.yearProperty.get() &&
            activeProperty.get() == other.activeProperty.get() &&
            ratingProperty.get() == other.ratingProperty.get() &&
            tagProperty.get() == other.tagProperty.get() &&
            items.referenceIds == other.items.referenceIds
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (nameProperty.get()?.hashCode() ?: 0)
        result = 31 * result + yearProperty.get()
        result = 31 * result + activeProperty.get().hashCode()
        result = 31 * result + ratingProperty.get().hashCode()
        result = 31 * result + (tagProperty.get()?.hashCode() ?: 0)
        result = 31 * result + items.referenceIds.hashCode()
        return result
    }

    override fun toString(): String = "FxSqlTestEntity(id=$id, name='${nameProperty.get()}')"
}

/** SQL table definition for [FxSqlTestEntity]. Scalar properties map to typed columns; item IDs are stored as CSV TEXT. */
object FxSqlTestEntityTableDef : SqlTableDef<FxSqlTestEntity> {
    override val tableName = "fx_sql_test_entities"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("name", ColumnType.VarcharType(200), nullable = false, primaryKey = false),
            ColumnDef("year", ColumnType.IntType, nullable = false, primaryKey = false),
            ColumnDef("active", ColumnType.BooleanType, nullable = false, primaryKey = false),
            ColumnDef("rating", ColumnType.DoubleType, nullable = false, primaryKey = false),
            ColumnDef("tag", ColumnType.TextType, nullable = true, primaryKey = false),
            ColumnDef("item_ids", ColumnType.TextType, nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): FxSqlTestEntity {
        val cols = table.columns.associateBy { it.name }
        val itemIdsText = row[cols["item_ids"]!! as Column<String>]
        val parsedIds =
            if (itemIdsText.isBlank()) emptyList()
            else itemIdsText.split(",").map { it.trim().toInt() }

        return FxSqlTestEntity(
            row[cols["id"]!! as Column<Int>],
            row[cols["name"]!! as Column<String>],
            row[cols["year"]!! as Column<Int>],
            row[cols["active"]!! as Column<Boolean>],
            row[cols["rating"]!! as Column<Double>],
            row[cols["tag"]!! as Column<String?>],
            parsedIds
        )
    }

    override fun toParams(entity: FxSqlTestEntity, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["name"]!! to entity.nameProperty.get(),
            cols["year"]!! to entity.yearProperty.get(),
            cols["active"]!! to entity.activeProperty.get(),
            cols["rating"]!! to entity.ratingProperty.get(),
            cols["tag"]!! to entity.tagProperty.get(),
            cols["item_ids"]!! to entity.items.referenceIds.joinToString(",")
        )
    }
}

/** Named [SqlRepository] for [FxSqlTestItem]. */
class FxSqlTestItemRepository(jdbcUrl: String) : SqlRepository<Int, FxSqlTestItem>(jdbcUrl, FxSqlTestItemTableDef)

/** Manual [LirpRegistryInfo] for [FxSqlTestItemRepository]. */
@Suppress("ClassName")
class `FxSqlTestItemRepository_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = FxSqlTestItem::class.java
}

/** Named [SqlRepository] for [FxSqlTestEntity]. */
class FxSqlTestEntityRepository(jdbcUrl: String) : SqlRepository<Int, FxSqlTestEntity>(jdbcUrl, FxSqlTestEntityTableDef)

/** Manual [LirpRegistryInfo] for [FxSqlTestEntityRepository]. */
@Suppress("ClassName")
class `FxSqlTestEntityRepository_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = FxSqlTestEntity::class.java
}

/**
 * Manual [LirpRefAccessor] for [FxSqlTestEntity]. Required because lirp-sql does not apply
 * the KSP processor, and the entity has an @Aggregate delegate that triggers the fail-fast
 * check in RegistryBase.discoverRefs.
 */
@Suppress("ktlint:standard:class-naming")
class `FxSqlTestEntity_LirpRefAccessor` : LirpRefAccessor<FxSqlTestEntity> {
    override val entries: List<RefEntry<*, FxSqlTestEntity>> = emptyList()

    override val collectionEntries: List<CollectionRefEntry<*, FxSqlTestEntity>> =
        listOf(
            @Suppress("UNCHECKED_CAST")
            CollectionRefEntry(
                refName = "items",
                idsGetter = { it.items.referenceIds },
                delegateGetter = { it.items as AggregateCollectionRef<*, *> },
                referencedClass = FxSqlTestItem::class.java,
                cascadeAction = CascadeAction.NONE,
                isOrdered = true
            )
        )

    override fun cancelAllBubbleUp(entity: FxSqlTestEntity) {
        entries.forEach { entry -> entry.delegateGetter(entity).cancelBubbleUp() }
    }
}