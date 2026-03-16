package net.transgressoft.lirp.persistence.json

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
class FlexibleJsonFileRepositoryTest : StringSpec({

    lateinit var repository: FlexibleJsonFileRepository
    lateinit var jsonFile: File

    beforeEach {
        jsonFile = tempfile("json-repository-test", ".json").also { it.deleteOnExit() }
        repository = FlexibleJsonFileRepository(jsonFile)
    }

    afterEach {
        repository.close()
    }

    "Repository should create and serialize strings, booleans and integers" {
        repository = FlexibleJsonFileRepository(jsonFile)
        val reactiveString = repository.getReactiveString("id1", "value1")
        val reactiveBoolean = repository.getReactiveBoolean("id2", true)
        val reactiveInt = repository.getReactiveInt("id3", 3)

        repository.contains(reactiveString.id) shouldBe true
        repository.findByUniqueId(reactiveBoolean.uniqueId) shouldBePresent { it shouldBe reactiveBoolean }
        repository.findFirst { it.value == 3 } shouldBePresent { it shouldBe reactiveInt }

        eventually(500.milliseconds) {
            jsonFile.readText().shouldEqualJson(
                """
                {
                    "id1": "value1",
                    "id2": true,
                    "id3": 3
                }"""
            )
        }
    }

    "Repository should be created from a json string of string, booleans and integers" {
        jsonFile.writeText(
            """
            {
                "property.1": "thevalue",
                "has_value": true,
                "number_of": 3
            }"""
        )

        val repository = FlexibleJsonFileRepository(jsonFile)

        repository.contains("property.1") shouldBe true
        repository.findByUniqueId("has_value-true") shouldBePresent { it.value shouldBe true }
        repository.findFirst { it.value == 3 } shouldBePresent { it.id shouldBe "number_of" }
    }

    "createReactiveX methods should return existing values from file, not overwrite with defaults" {
        jsonFile.writeText(
            """
            {
                "server.name": "Production Server",
                "server.port": 9000,
                "debug.mode": true
            }"""
        )

        val repository = FlexibleJsonFileRepository(jsonFile)

        repository.contains("server.name") shouldBe true
        repository.contains("server.port") shouldBe true
        repository.contains("debug.mode") shouldBe true

        // When we call createReactiveX with different default values,
        // it should return the EXISTING values from the file, not the defaults
        val serverName = repository.getReactiveString("server.name", "Default Server")
        val serverPort = repository.getReactiveInt("server.port", 8080)
        val debugMode = repository.getReactiveBoolean("debug.mode", false)

        // These should have the values from the file
        serverName.value shouldBe "Production Server"
        serverPort.value shouldBe 9000
        debugMode.value shouldBe true

        // Changing values should persist to file
        serverPort.value = 9090

        eventually(500.milliseconds) {
            val updatedContent = jsonFile.readText()
            updatedContent.shouldEqualJson(
                """
                {
                    "server.name": "Production Server",
                    "server.port": 9090,
                    "debug.mode": true
                }"""
            )
        }
    }

    "FlexibleJsonFileRepository getReactiveString throws IllegalStateException when existing entry is ReactiveInt" {
        repository.getReactiveInt("myId", 42)

        val exception =
            shouldThrowExactly<IllegalStateException> {
                repository.getReactiveString("myId")
            }
        exception.message shouldContain "myId"
        exception.message shouldContain "ReactiveString"
        exception.message shouldContain "ReactiveInt"
    }

    "FlexibleJsonFileRepository getReactiveBoolean throws IllegalStateException when existing entry is ReactiveString" {
        repository.getReactiveString("myId", "hello")

        val exception =
            shouldThrowExactly<IllegalStateException> {
                repository.getReactiveBoolean("myId")
            }
        exception.message shouldContain "myId"
        exception.message shouldContain "ReactiveBoolean"
        exception.message shouldContain "ReactiveString"
    }

    "FlexibleJsonFileRepository getReactiveInt throws IllegalStateException when existing entry is ReactiveBoolean" {
        repository.getReactiveBoolean("myId", true)

        val exception =
            shouldThrowExactly<IllegalStateException> {
                repository.getReactiveInt("myId")
            }
        exception.message shouldContain "myId"
        exception.message shouldContain "ReactiveInt"
        exception.message shouldContain "ReactiveBoolean"
    }
})