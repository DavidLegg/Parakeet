package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delay
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.createDirectories
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.createDirectory
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.delete
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.deleteRecursively
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.exists
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.isDirectory
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.isFile
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.list
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.readContent
import gov.nasa.jpl.parakeet.general.file_system.FileSystemOperations.writeFile
import gov.nasa.jpl.parakeet.general.testing.UnitTesting.runUnitTest
import kotlinx.serialization.json.Json
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class FileSystemModelTest {
    private val start = Instant.parse("2020-01-01T00:00:00Z")
    private fun p(s: String) = Path.of(s)

    @Test
    fun `new file system has an empty root created at simulation start`() {
        runUnitTest(start, { FileSystemModel("fs") }) { fs ->
            val root = fs.getValue()
            assertEquals(start, root.createdTime)
            assertTrue(root.children.isEmpty())
            assertTrue(fs.isDirectory(p("/")))
        }
    }

    @Test
    fun `write, read, and size of files`() {
        runUnitTest(start, { FileSystemModel("fs") }) { fs ->
            fs.createDirectories(p("/a/b"))
            fs.writeFile(p("/a/b/f.txt"), FileContent.of("hello"))
            fs.writeFile(p("/a/g.bin"), size = 100)
            assertTrue(fs.isFile(p("/a/b/f.txt")))
            assertEquals("hello", fs.readContent(p("/a/b/f.txt"))!!.toByteArray().decodeToString())
            assertNull(fs.readContent(p("/a/g.bin")))
            assertEquals(105, fs.getValue().totalSize)
            assertEquals(2, fs.getValue().fileCount)
            assertEquals(setOf("b", "g.bin"), fs.list(p("/a")))
        }
    }

    @Test
    fun `paths are normalized and must be absolute`() {
        runUnitTest(start, { FileSystemModel("fs") }) { fs ->
            fs.createDirectory(p("/a"))
            assertTrue(fs.exists(p("/a/./b/../")))
            assertTrue(fs.exists(p("/../a")))
            assertFailsWith<IllegalArgumentException> { fs.get(p("a")) }
        }
    }

    @Test
    fun `get returns null through files and missing components`() {
        runUnitTest(start, { FileSystemModel("fs") }) { fs ->
            fs.writeFile(p("/f"))
            assertNull(fs.get(p("/f/x")))
            assertNull(fs.get(p("/missing/x")))
        }
    }

    @Test
    fun `invalid changes throw IOExceptions and leave the file system untouched`() {
        runUnitTest(start, { FileSystemModel("fs") }) { fs ->
            val before = fs.getValue()
            assertFailsWith<NoSuchFileException> { fs.writeFile(p("/missing/f")) }
            fs.createDirectory(p("/d"))
            assertFailsWith<FileAlreadyExistsException> { fs.createDirectory(p("/d")) }
            assertFailsWith<FileAlreadyExistsException> { fs.writeFile(p("/d")) }
            fs.writeFile(p("/d/f"))
            assertFailsWith<DirectoryNotEmptyException> { fs.delete(p("/d")) }
            assertFailsWith<NoSuchFileException> { fs.delete(p("/nope")) }
            assertFailsWith<java.io.IOException> { fs.change(p("/")) { null } }
            // The resource is not faulted
            fs.getValue()
            fs.deleteRecursively(p("/d"))
            assertEquals(before.children, fs.getValue().children)
            fs.deleteRecursively(p("/d"))
        }
    }

    @Test
    fun `parent modified time follows Unix semantics`() {
        runUnitTest(start, { FileSystemModel("fs") }) { fs ->
            fs.createDirectory(p("/d"))
            delay(1.hours)
            val t1 = start + 1.hours
            fs.writeFile(p("/d/f"))
            assertEquals(t1, (fs.get(p("/d")) as Directory).modifiedTime)
            assertEquals(start, fs.getValue().modifiedTime)
            delay(1.hours)
            // Same-kind overwrite leaves the parent alone, and preserves creation time
            fs.writeFile(p("/d/f"), size = 5)
            assertEquals(t1, (fs.get(p("/d")) as Directory).modifiedTime)
            val f = fs.get(p("/d/f")) as File
            assertEquals(t1, f.createdTime)
            assertEquals(start + 2.hours, f.modifiedTime)
            fs.delete(p("/d/f"))
            assertEquals(start + 2.hours, (fs.get(p("/d")) as Directory).modifiedTime)
        }
    }

    @Test
    fun `equality is by content and serialization round-trips`() {
        val t = start
        val a = Directory(t, t, mapOf("x" to File(t, t, 3, FileContent.of("abc")), "y" to Directory(t, t)))
        val b = Directory(t, t, mapOf("y" to Directory(t, t), "x" to File(t, t, 3, FileContent.of("abc"))))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(children = a.children + ("z" to File(t, t))))
        assertNotEquals(a, a.copy(modifiedTime = t + 1.hours))
        val decoded = Json.decodeFromString<Directory>(Json.encodeToString(Directory.serializer(), a))
        assertEquals(a, decoded)
        assertEquals(3, decoded.totalSize)
        assertEquals(1, decoded.fileCount)
    }

    @Test
    fun `initial root is honored`() {
        val root = Directory(start, start, mapOf("f" to File(start, start, 7)))
        runUnitTest(start, { FileSystemModel("fs", root) }) { fs ->
            assertEquals(7, fs.getValue().totalSize)
        }
    }
}
