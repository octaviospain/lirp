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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.ColumnType
import java.math.BigDecimal
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Test entity covering all 12 [ColumnType] variants, used to verify DDL type mapping
 * and CRUD round-trip correctness in integration tests.
 *
 * Date and datetime fields use `kotlinx.datetime` types because JetBrains Exposed's
 * `exposed-kotlin-datetime` module stores and retrieves [LocalDate] and [LocalDateTime] instances.
 * The `uuid_val` field uses [kotlin.uuid.Uuid] because Exposed v1's `uuid()` column operates on that type.
 * The `enum_val` field uses [String] because [ColumnType.EnumType] is stored as `VARCHAR(255)`.
 */
@OptIn(ExperimentalUuidApi::class)
class AllTypesEntity(override val id: Int) : ReactiveEntityBase<Int, AllTypesEntity>() {
    var longVal: Long by reactiveProperty(0L)
    var textVal: String by reactiveProperty("")
    var boolVal: Boolean by reactiveProperty(false)
    var doubleVal: Double by reactiveProperty(0.0)
    var floatVal: Float by reactiveProperty(0.0f)
    var uuidVal: Uuid by reactiveProperty(Uuid.random())
    var dateVal: LocalDate by reactiveProperty(LocalDate(2025, 1, 1))
    var dateTimeVal: LocalDateTime by reactiveProperty(LocalDateTime(2025, 1, 1, 0, 0, 0))
    var varcharVal: String by reactiveProperty("")
    var decimalVal: BigDecimal by reactiveProperty(BigDecimal.ZERO)
    var enumVal: String by reactiveProperty("")
    override val uniqueId: String get() = id.toString()

    override fun clone(): AllTypesEntity =
        AllTypesEntity(id).also { copy ->
            copy.withEventsDisabled {
                copy.longVal = longVal
                copy.textVal = textVal
                copy.boolVal = boolVal
                copy.doubleVal = doubleVal
                copy.floatVal = floatVal
                copy.uuidVal = uuidVal
                copy.dateVal = dateVal
                copy.dateTimeVal = dateTimeVal
                copy.varcharVal = varcharVal
                copy.decimalVal = decimalVal
                copy.enumVal = enumVal
            }
        }
}