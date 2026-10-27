package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.isEmpty
import gov.nasa.jpl.parakeet.general.testing.UnitTesting.runUnitTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.time.Instant

class FileSystemModelTest {
    @Test
    fun `default model is an empty root directory`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            val root = fsm.getValue()
            assertIs<Directory>(root)
            assert(root.isEmpty())
        }
    }
}