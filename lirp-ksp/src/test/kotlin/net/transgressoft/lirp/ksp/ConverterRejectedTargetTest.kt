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

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * KSP compilation tests for enforcement in [TableDefProcessor]: converters are rejected
 * on primary key columns, `@Version` columns, and `@ToOneAggregate` FK scalar columns.
 * Each rejection emits a distinct diagnostic naming the property FQN and the kind of column.
 */
@OptIn(ExperimentalCompilerApi::class)
class ConverterRejectedTargetTest : StringSpec({

    "TableDefProcessor rejects converter on primary key column with a diagnostic naming the property FQN" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PkConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object PkConverter : ColumnConverter<Int, Int> {
                        override val sqlType = ColumnType.IntType
                        override fun toSql(value: Int): Int = value
                        override fun fromSql(raw: Int): Int = raw
                    }

                    @PersistenceMapping
                    data class PkConverterEntity(
                        @PersistenceProperty(converter = PkConverter::class) override val id: Int
                    ) : ReactiveEntityBase<Int, PkConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = PkConverterEntity(id)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "primary key column"
        result.messages shouldContain "test.PkConverterEntity.id"
    }

    "TableDefProcessor rejects converter on @Version column with a diagnostic naming the property FQN" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "VersionConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty
                    import net.transgressoft.lirp.persistence.Version

                    object VersionConverter : ColumnConverter<Long, Long> {
                        override val sqlType = ColumnType.LongType
                        override fun toSql(value: Long): Long = value
                        override fun fromSql(raw: Long): Long = raw
                    }

                    @PersistenceMapping
                    class VersionConverterEntity(override val id: Int) : ReactiveEntityBase<Int, VersionConverterEntity>() {
                        @Version
                        @PersistenceProperty(converter = VersionConverter::class)
                        var version: Long by reactiveProperty(0L)
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VersionConverterEntity(id)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Version column"
        result.messages shouldContain "test.VersionConverterEntity.version"
    }

    "TableDefProcessor rejects converter on @ToOneAggregate single-ref FK scalar column with a diagnostic naming the property FQN" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "AggregateFkConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    object FkConverter : ColumnConverter<Int, Int> {
                        override val sqlType = ColumnType.IntType
                        override fun toSql(value: Int): Int = value
                        override fun fromSql(raw: Int): Int = raw
                    }

                    @PersistenceMapping
                    class Customer(override val id: Int) : ReactiveEntityBase<Int, Customer>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Customer(id)
                    }

                    @PersistenceMapping
                    class Order(override val id: Long, customerId: Int) : ReactiveEntityBase<Long, Order>() {
                        @PersistenceProperty(converter = FkConverter::class)
                        @ToOneAggregate(target = Customer::class, onDelete = CascadeAction.RESTRICT)
                        var customerId: Int by reactiveProperty(customerId)
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Order(id, customerId)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@ToOneAggregate single-ref FK"
        result.messages shouldContain "test.Order.customerId"
    }
})