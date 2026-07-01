package net.transgressoft.fleet.common

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.sql.PostgresContainerSupport
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Base test class providing per-test Postgres isolation for LIRP fleet integration tests.
 *
 * Each test receives a fresh [HikariDataSource] backed by a shared Testcontainers PostgreSQL
 * container, a fresh [LirpContext], and isolated [ReactiveScope] coroutine scopes. Isolating
 * the scopes per-test prevents debounce coroutines from one test leaking into the next test's
 * data source — the global `ioScope` is single-threaded and queues debounce jobs across all
 * live repositories, so without isolation a slow flush from test N can fire after test N's
 * data source is closed and test N+1's data source is open.
 *
 * Close all repositories explicitly before the test body returns to flush their debounce
 * pipeline before the scopes and data source are torn down in `afterEach`.
 */
abstract class FleetTestBase(body: FleetTestBase.() -> Unit = {}) : StringSpec() {

    lateinit var dataSource: HikariDataSource
    lateinit var ctx: LirpContext

    private lateinit var testFlowScope: CoroutineScope
    private lateinit var testIoScope: CoroutineScope
    private lateinit var previousFlowScope: CoroutineScope
    private lateinit var previousIoScope: CoroutineScope

    init {
        beforeEach {
            testFlowScope = CoroutineScope(Dispatchers.Default.limitedParallelism(4) + SupervisorJob())
            testIoScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
            previousFlowScope = ReactiveScope.flowScope
            previousIoScope = ReactiveScope.ioScope
            ReactiveScope.flowScope = testFlowScope
            ReactiveScope.ioScope = testIoScope
            dataSource = PostgresContainerSupport.buildDataSource()
            ctx = LirpContext()
        }

        afterEach {
            ctx.close()
            dataSource.close()
            testFlowScope.cancel()
            testIoScope.cancel()
            ReactiveScope.flowScope = previousFlowScope
            ReactiveScope.ioScope = previousIoScope
        }

        body()
    }
}