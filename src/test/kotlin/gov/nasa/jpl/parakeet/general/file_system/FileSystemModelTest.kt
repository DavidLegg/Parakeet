package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delayUntil
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.cp
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.cpRecursive
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.getRoot
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.glob
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.mkdir
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.mkdirP
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.mv
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.rm
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.WriteMode
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.rmLeaf
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.rmRecursive
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.rmdir
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.touch
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.write
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.Directory
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.File
import gov.nasa.jpl.parakeet.general.file_system.FileSystemTestUtils.checkFileNode
import gov.nasa.jpl.parakeet.general.testing.UnitTesting.runUnitTest
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FileSystemModelTest {
    @Test
    fun `default model is an empty root directory`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            checkFileNode(fsm.getRoot()) {
                isDir(start, start, 0, 0)
            }
        }
    }

    @Test
    fun `model can start from a non-empty initial directory`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val past = start - 1.hours
        val content = "hello".toByteArray()
        val initialDirectory = Directory("", past, past, mapOf(
            "docs" to Directory("docs", past, past, mapOf(
                "readme.txt" to File("readme.txt", past, past, content),
            )),
            "empty.txt" to File("empty.txt", past),
        ))
        runUnitTest(start, { FileSystemModel("fs", initialDirectory) }) { fsm ->
            checkFileNode(fsm.getRoot()) {
                isDir(past, past, 2, content.size,
                    "docs" to {
                        isDir(past, past, 1, content.size,
                            "readme.txt" to { isFile(past, past, content, content.size) },
                        )
                    },
                    "empty.txt" to { isFile(past, past, null, 0) },
                )
            }
        }
    }

    @Test
    fun `model rejects an initial directory with a non-empty name`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        runUnitTest(start, { 
            assertFailsWith<IllegalArgumentException> {
                FileSystemModel("fs", Directory("root", start))
            }
        }) {}
    }

    @Test
    fun `directories can be added to the model`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            checkFileNode(fsm.getRoot()) {
                isDir(start, start, 0, 0)
            }

            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 0, 0,
                    "foo" to { isDir(t1, t1, 0, 0) },
                )
            }

            delayUntil(t2)
            fsm.mkdir(Path("/foo/bar"))
            fsm.mkdir(Path("/foo/baz"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 0, 0,
                    "foo" to {
                        isDir(t1, t2, 0, 0,
                            "bar" to { isDir(t2, t2, 0, 0) },
                            "baz" to { isDir(t2, t2, 0, 0) },
                        )
                    },
                )
            }

            delayUntil(t3)
            fsm.mkdirP(Path("/foo/bar"))
            fsm.mkdirP(Path("/foo/baz/qux/quz"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 0, 0,
                    "foo" to {
                        isDir(t1, t2, 0, 0,
                            "bar" to { isDir(t2, t2, 0, 0) },
                            "baz" to {
                                isDir(t2, t3, 0, 0,
                                    "qux" to {
                                        isDir(t3, t3, 0, 0,
                                            "quz" to { isDir(t3, t3, 0, 0) }
                                        )
                                    }
                                )
                            },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `mkdir fails if the path exists, the parent is missing, or the parent is a file`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/dir"))
            fsm.write(Path("/file.txt"), sizeBytes = 10)
            val rootAtT1 = fsm.getRoot()

            delayUntil(t2)
            assertFailsWith<IllegalArgumentException> { fsm.mkdir(Path("/dir")) }
            assertFailsWith<IllegalArgumentException> { fsm.mkdir(Path("/file.txt")) }
            assertFailsWith<IllegalArgumentException> { fsm.mkdir(Path("/missing/child")) }
            assertFailsWith<IllegalArgumentException> { fsm.mkdir(Path("/file.txt/child")) }
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `mkdirP is a no-op for existing directories`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdirP(Path("/foo/bar"))
            val rootAtT1 = fsm.getRoot()

            delayUntil(t2)
            fsm.mkdirP(Path("/"))
            fsm.mkdirP(Path("/foo"))
            fsm.mkdirP(Path("/foo/bar"))
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `mkdirP fails if any component of the path is a file`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/dir"))
            fsm.write(Path("/file.txt"), sizeBytes = 10)
            fsm.write(Path("/dir/inner.txt"), sizeBytes = 10)
            val rootAtT1 = fsm.getRoot()

            delayUntil(t2)
            assertFailsWith<IllegalArgumentException> { fsm.mkdirP(Path("/file.txt")) }
            assertFailsWith<IllegalArgumentException> { fsm.mkdirP(Path("/dir/inner.txt")) }
            assertFailsWith<IllegalArgumentException> { fsm.mkdirP(Path("/file.txt/child")) }
            assertFailsWith<IllegalArgumentException> { fsm.mkdirP(Path("/dir/inner.txt/a/b")) }
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `touch creates empty files and updates modified times`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.touch(Path("/foo/a.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, 0,
                    "foo" to {
                        isDir(t1, t1, 1, 0,
                            "a.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t2)
            fsm.touch(Path("/foo/a.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, 0,
                    "foo" to {
                        isDir(t1, t1, 1, 0,
                            "a.txt" to { isFile(t1, t2, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t3)
            fsm.touch(Path("/foo/b.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, 0,
                    "foo" to {
                        isDir(t1, t3, 2, 0,
                            "a.txt" to { isFile(t1, t2, sizeBytes = 0) },
                            "b.txt" to { isFile(t3, t3, sizeBytes = 0) },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `touch fails if the parent is missing or is a file`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.write(Path("/file.txt"), sizeBytes = 10)
            val rootAtT1 = fsm.getRoot()

            delayUntil(t2)
            assertFailsWith<IllegalArgumentException> { fsm.touch(Path("/missing/a.txt")) }
            assertFailsWith<IllegalArgumentException> { fsm.touch(Path("/file.txt/a.txt")) }
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `write in create-only mode fails if the file already exists`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val content1 = "hello".toByteArray()
        val content2 = "goodbye".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            // Write with content:
            fsm.write(Path("/a.txt"), content1, writeMode = WriteMode.CreateOnly)
            // Size-only modeling:
            fsm.write(Path("/b.txt"), sizeBytes = 1024, writeMode = WriteMode.CreateOnly)
            val rootAtT1 = fsm.getRoot()
            checkFileNode(rootAtT1) {
                isDir(start, t1, 2, content1.size + 1024,
                    "a.txt" to { isFile(t1, t1, content1, content1.size) },
                    "b.txt" to { isFile(t1, t1, null, 1024) },
                )
            }

            delayUntil(t2)
            assertFailsWith<IllegalArgumentException> {
                fsm.write(Path("/a.txt"), content2, writeMode = WriteMode.CreateOnly)
            }
            assertFailsWith<IllegalArgumentException> {
                fsm.write(Path("/b.txt"), sizeBytes = 2048, writeMode = WriteMode.CreateOnly)
            }
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `write in create-or-overwrite mode creates or replaces files`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val content1 = "hello".toByteArray()
        val content2 = "goodbye".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            // Write with content:
            fsm.write(Path("/a.txt"), content1, writeMode = WriteMode.CreateOrOverwrite)
            // Size-only modeling:
            fsm.write(Path("/b.txt"), sizeBytes = 1024, writeMode = WriteMode.CreateOrOverwrite)
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content1.size + 1024,
                    "a.txt" to { isFile(t1, t1, content1, content1.size) },
                    "b.txt" to { isFile(t1, t1, null, 1024) },
                )
            }

            delayUntil(t2)
            fsm.write(Path("/a.txt"), content2, writeMode = WriteMode.CreateOrOverwrite)
            fsm.write(Path("/b.txt"), sizeBytes = 2048, writeMode = WriteMode.CreateOrOverwrite)
            // Overwriting preserves the created time, replaces the content and size, and doesn't modify the parent directory.
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content2.size + 2048,
                    "a.txt" to { isFile(t1, t2, content2, content2.size) },
                    "b.txt" to { isFile(t1, t2, null, 2048) },
                )
            }
        }
    }

    @Test
    fun `write in append mode creates or extends files`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val content1 = "hello".toByteArray()
        val content2 = "goodbye".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            // Write with content:
            fsm.write(Path("/a.txt"), content1, writeMode = WriteMode.Append)
            // Size-only modeling:
            fsm.write(Path("/b.txt"), sizeBytes = 1024, writeMode = WriteMode.Append)
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content1.size + 1024,
                    "a.txt" to { isFile(t1, t1, content1, content1.size) },
                    "b.txt" to { isFile(t1, t1, null, 1024) },
                )
            }

            delayUntil(t2)
            fsm.write(Path("/a.txt"), content2, writeMode = WriteMode.Append)
            fsm.write(Path("/b.txt"), sizeBytes = 2048, writeMode = WriteMode.Append)
            // Appending preserves the created time, concatenates content, adds sizes, and doesn't modify the parent directory.
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content1.size + content2.size + 1024 + 2048,
                    "a.txt" to { isFile(t1, t2, content1 + content2, content1.size + content2.size) },
                    "b.txt" to { isFile(t1, t2, null, 1024 + 2048) },
                )
            }
        }
    }

    @Test
    fun `write fails if the parent is missing or is a file`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.write(Path("/file.txt"), sizeBytes = 10)
            val rootAtT1 = fsm.getRoot()

            delayUntil(t2)
            for (writeMode in WriteMode.entries) {
                assertFailsWith<IllegalArgumentException> {
                    fsm.write(Path("/missing/a.txt"), sizeBytes = 5, writeMode = writeMode)
                }
                assertFailsWith<IllegalArgumentException> {
                    fsm.write(Path("/file.txt/a.txt"), sizeBytes = 5, writeMode = writeMode)
                }
            }
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `write fails if the path is a directory`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/dir"))
            fsm.write(Path("/dir/a.txt"), sizeBytes = 10)
            fsm.mkdir(Path("/empty-dir"))
            val rootAtT1 = fsm.getRoot()

            delayUntil(t2)
            for (writeMode in WriteMode.entries) {
                assertFailsWith<IllegalArgumentException> {
                    fsm.write(Path("/dir"), sizeBytes = 5, writeMode = writeMode)
                }
                assertFailsWith<IllegalArgumentException> {
                    fsm.write(Path("/empty-dir"), sizeBytes = 5, writeMode = writeMode)
                }
            }
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `rm removes individual files`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.touch(Path("/foo/a.txt"))
            fsm.touch(Path("/foo/b.txt"))
            fsm.mkdir(Path("/empty-dir"))
            fsm.mkdir(Path("/nonempty-dir"))
            fsm.touch(Path("/nonempty-dir/x.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 3, 0,
                    "foo" to {
                        isDir(t1, t1, 2, 0,
                            "a.txt" to { isFile(t1, t1, sizeBytes = 0) },
                            "b.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t2)
            fsm.rm(Path("/foo/a.txt"))
            val rootAtT2 = fsm.getRoot()
            checkFileNode(rootAtT2) {
                isDir(start, t1, 2, 0,
                    "foo" to {
                        isDir(t1, t2, 1, 0,
                            "b.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }
            // rm only removes files, so it must fail on both empty and non-empty directories
            // and it must not change the file system when it fails
            assertFailsWith<IllegalArgumentException> { fsm.rm(Path("/empty-dir")) }
            assertEquals(rootAtT2, fsm.getRoot())
            assertFailsWith<IllegalArgumentException> { fsm.rm(Path("/nonempty-dir")) }
            assertEquals(rootAtT2, fsm.getRoot())
            // In a slight departure from shell `rm` semantics, our rm quietly tolerates a missing file.
            // This allows concurrent tasks to both `rm` a file, without error. This matches the intent of "just delete the file".
            fsm.rm(Path("/foo/a.txt"))
            assertEquals(rootAtT2, fsm.getRoot())
        }
    }

    @Test
    fun `rmdir removes only empty directories`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.touch(Path("/foo/a.txt"))
            fsm.mkdir(Path("/empty-dir"))
            fsm.mkdir(Path("/nonempty-dir"))
            fsm.touch(Path("/nonempty-dir/x.txt"))
            val rootAtT1 = fsm.getRoot()
            checkFileNode(rootAtT1) {
                isDir(start, t1, 2, 0,
                    "foo" to {
                        isDir(t1, t1, 1, 0,
                            "a.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t2)
            // rmdir fails on files and non-empty directories, and must not change the file system when it fails
            assertFailsWith<IllegalArgumentException> { fsm.rmdir(Path("/foo/a.txt")) }
            assertEquals(rootAtT1, fsm.getRoot())
            assertFailsWith<IllegalArgumentException> { fsm.rmdir(Path("/nonempty-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())

            // rmdir succeeds on an empty directory
            fsm.rmdir(Path("/empty-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t2, 2, 0,
                    "foo" to {
                        isDir(t1, t1, 1, 0,
                            "a.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `rmLeaf removes files and empty directories`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.touch(Path("/foo/a.txt"))
            fsm.mkdir(Path("/empty-dir"))
            fsm.mkdir(Path("/nonempty-dir"))
            fsm.touch(Path("/nonempty-dir/x.txt"))
            val rootAtT1 = fsm.getRoot()
            checkFileNode(rootAtT1) {
                isDir(start, t1, 2, 0,
                    "foo" to {
                        isDir(t1, t1, 1, 0,
                            "a.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t2)
            // rmLeaf fails on non-empty directories, and must not change the file system when it fails
            assertFailsWith<IllegalArgumentException> { fsm.rmLeaf(Path("/nonempty-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())

            // rmLeaf succeeds on a file
            fsm.rmLeaf(Path("/foo/a.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, 0,
                    "foo" to { isDir(t1, t2, 0, 0) },
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t3)
            // rmLeaf succeeds on an empty directory
            fsm.rmLeaf(Path("/empty-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t3, 1, 0,
                    "foo" to { isDir(t1, t2, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `rmRecursive removes files and directories with their contents`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        val t4 = t3 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdirP(Path("/nonempty-dir/sub"))
            fsm.touch(Path("/nonempty-dir/a.txt"))
            fsm.touch(Path("/nonempty-dir/sub/b.txt"))
            fsm.mkdir(Path("/empty-dir"))
            fsm.mkdir(Path("/other-dir"))
            fsm.touch(Path("/other-dir/c.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 3, 0,
                    "nonempty-dir" to {
                        isDir(t1, t1, 2, 0,
                            "a.txt" to { isFile(t1, t1, sizeBytes = 0) },
                            "sub" to {
                                isDir(t1, t1, 1, 0,
                                    "b.txt" to { isFile(t1, t1, sizeBytes = 0) },
                                )
                            },
                        )
                    },
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "other-dir" to {
                        isDir(t1, t1, 1, 0,
                            "c.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t2)
            // rmRecursive removes a non-empty directory along with everything inside it
            fsm.rmRecursive(Path("/nonempty-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t2, 1, 0,
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "other-dir" to {
                        isDir(t1, t1, 1, 0,
                            "c.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t3)
            // rmRecursive also removes empty directories
            fsm.rmRecursive(Path("/empty-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t3, 1, 0,
                    "other-dir" to {
                        isDir(t1, t1, 1, 0,
                            "c.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }

            delayUntil(t4)
            // rmRecursive also removes files
            fsm.rmRecursive(Path("/other-dir/c.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t3, 0, 0,
                    "other-dir" to { isDir(t1, t4, 0, 0) },
                )
            }
        }
    }

    @Test
    fun `mv moves a file into a directory, to a new name, or over an existing file`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        val t4 = t3 + 1.minutes
        val t5 = t4 + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/src-dir"))
            fsm.mkdir(Path("/dst-dir"))
            fsm.write(Path("/src-dir/a.txt"), content)
            val rootAtT1 = fsm.getRoot()
            checkFileNode(rootAtT1) {
                isDir(start, t1, 1, content.size,
                    "src-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                    "dst-dir" to { isDir(t1, t1, 0, 0) },
                )
            }

            delayUntil(t2)
            // mv fails if src doesn't exist, and must not change the file system when it fails
            assertFailsWith<IllegalArgumentException> { fsm.mv(Path("/src-dir/missing.txt"), Path("/dst-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())

            // mv into an existing directory keeps the file's name
            fsm.mv(Path("/src-dir/a.txt"), Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, content.size,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t2, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t3)
            // mv to a path that doesn't exist renames the file
            fsm.mv(Path("/dst-dir/a.txt"), Path("/dst-dir/b.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, content.size,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t3, 1, content.size,
                            "b.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t4)
            fsm.touch(Path("/dst-dir/c.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content.size,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t4, 2, content.size,
                            "b.txt" to { isFile(t1, t1, content, content.size) },
                            "c.txt" to { isFile(t4, t4, null, 0) },
                        )
                    },
                )
            }

            delayUntil(t5)
            // mv onto an existing file overwrites it
            fsm.mv(Path("/dst-dir/b.txt"), Path("/dst-dir/c.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, content.size,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t5, 1, content.size,
                            "c.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `mv moves an empty directory`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        val t4 = t3 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdirP(Path("/src-dir/empty-dir"))
            fsm.mkdir(Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 0, 0,
                    "src-dir" to {
                        isDir(t1, t1, 0, 0,
                            "empty-dir" to { isDir(t1, t1, 0, 0) },
                        )
                    },
                    "dst-dir" to { isDir(t1, t1, 0, 0) },
                )
            }

            delayUntil(t2)
            // mv into an existing directory keeps the directory's name
            fsm.mv(Path("/src-dir/empty-dir"), Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 0, 0,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t2, 0, 0,
                            "empty-dir" to { isDir(t1, t1, 0, 0) },
                        )
                    },
                )
            }

            delayUntil(t3)
            // mv to a path that doesn't exist renames the directory
            fsm.mv(Path("/dst-dir/empty-dir"), Path("/dst-dir/renamed-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 0, 0,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t3, 0, 0,
                            "renamed-dir" to { isDir(t1, t1, 0, 0) },
                        )
                    },
                )
            }

            delayUntil(t4)
            fsm.touch(Path("/file.txt"))
            val rootAtT4 = fsm.getRoot()
            checkFileNode(rootAtT4) {
                isDir(start, t4, 1, 0,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t3, 0, 0,
                            "renamed-dir" to { isDir(t1, t1, 0, 0) },
                        )
                    },
                    "file.txt" to { isFile(t4, t4, null, 0) },
                )
            }
            // mv cannot overwrite a non-directory with a directory.
            // It must fail, and must leave both the source and the destination untouched.
            assertFailsWith<IllegalArgumentException> { fsm.mv(Path("/dst-dir/renamed-dir"), Path("/file.txt")) }
            assertEquals(rootAtT4, fsm.getRoot())
        }
    }

    @Test
    fun `mv moves a non-empty directory with its contents`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        val t4 = t3 + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdirP(Path("/src-dir/proj/sub"))
            fsm.write(Path("/src-dir/proj/a.txt"), content)
            fsm.touch(Path("/src-dir/proj/sub/b.txt"))
            fsm.mkdir(Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content.size,
                    "src-dir" to {
                        isDir(t1, t1, 2, content.size,
                            "proj" to {
                                isDir(t1, t1, 2, content.size,
                                    "a.txt" to { isFile(t1, t1, content, content.size) },
                                    "sub" to {
                                        isDir(t1, t1, 1, 0,
                                            "b.txt" to { isFile(t1, t1, null, 0) },
                                        )
                                    },
                                )
                            },
                        )
                    },
                    "dst-dir" to { isDir(t1, t1, 0, 0) },
                )
            }

            delayUntil(t2)
            // mv into an existing directory moves the whole subtree, without modifying anything inside it
            fsm.mv(Path("/src-dir/proj"), Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content.size,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t2, 2, content.size,
                            "proj" to {
                                isDir(t1, t1, 2, content.size,
                                    "a.txt" to { isFile(t1, t1, content, content.size) },
                                    "sub" to {
                                        isDir(t1, t1, 1, 0,
                                            "b.txt" to { isFile(t1, t1, null, 0) },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }

            delayUntil(t3)
            // mv to a path that doesn't exist renames the directory, again leaving its contents alone
            fsm.mv(Path("/dst-dir/proj"), Path("/dst-dir/renamed-proj"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content.size,
                    "src-dir" to { isDir(t1, t2, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t3, 2, content.size,
                            "renamed-proj" to {
                                isDir(t1, t1, 2, content.size,
                                    "a.txt" to { isFile(t1, t1, content, content.size) },
                                    "sub" to {
                                        isDir(t1, t1, 1, 0,
                                            "b.txt" to { isFile(t1, t1, null, 0) },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }

            delayUntil(t4)
            val rootAtT4 = fsm.getRoot()
            // mv cannot move a project into its own subdirectory
            // It must fail, and must leave the file system untouched
            assertFailsWith<IllegalArgumentException> { fsm.mv(Path("/dst-dir/renamed-proj"), Path("/dst-dir/renamed-proj/sub")) }
            assertEquals(rootAtT4, fsm.getRoot())
        }
    }

    @Test
    fun `cp copies a file into a directory, to a new name, or over an existing file`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        val t4 = t3 + 1.minutes
        val t5 = t4 + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/src-dir"))
            fsm.mkdir(Path("/dst-dir"))
            fsm.write(Path("/src-dir/a.txt"), content)
            val rootAtT1 = fsm.getRoot()
            checkFileNode(rootAtT1) {
                isDir(start, t1, 1, content.size,
                    "src-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                    "dst-dir" to { isDir(t1, t1, 0, 0) },
                )
            }

            delayUntil(t2)
            // cp fails if src doesn't exist, and must not change the file system when it fails
            assertFailsWith<IllegalArgumentException> { fsm.cp(Path("/src-dir/missing.txt"), Path("/dst-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())

            // cp into an existing directory keeps the file's name.
            // The copy is a new file, created now, and the original is left alone.
            fsm.cp(Path("/src-dir/a.txt"), Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 2, content.size * 2,
                    "src-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                    "dst-dir" to {
                        isDir(t1, t2, 1, content.size,
                            "a.txt" to { isFile(t2, t2, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t3)
            // cp to a path that doesn't exist creates a copy under the new name
            fsm.cp(Path("/dst-dir/a.txt"), Path("/dst-dir/b.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 3, content.size * 3,
                    "src-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                    "dst-dir" to {
                        isDir(t1, t3, 2, content.size * 2,
                            "a.txt" to { isFile(t2, t2, content, content.size) },
                            "b.txt" to { isFile(t3, t3, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t4)
            fsm.touch(Path("/dst-dir/c.txt"))

            delayUntil(t5)
            // cp onto an existing file overwrites it in place.
            // The existing file keeps its created time, and takes the source's content and size.
            fsm.cp(Path("/dst-dir/b.txt"), Path("/dst-dir/c.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 4, content.size * 4,
                    "src-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                    "dst-dir" to {
                        isDir(t1, t4, 3, content.size * 3,
                            "a.txt" to { isFile(t2, t2, content, content.size) },
                            "b.txt" to { isFile(t3, t3, content, content.size) },
                            "c.txt" to { isFile(t4, t5, content, content.size) },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `non-recursive cp fails to copy directories`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/empty-dir"))
            fsm.mkdir(Path("/dst-dir"))
            fsm.mkdir(Path("/nonempty-dir"))
            fsm.touch(Path("/nonempty-dir/a.txt"))
            val rootAtT1 = fsm.getRoot()
            checkFileNode(rootAtT1) {
                isDir(start, t1, 1, 0,
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "dst-dir" to { isDir(t1, t1, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, 0,
                            "a.txt" to { isFile(t1, t1, null, 0) },
                        )
                    },
                )
            }

            delayUntil(t2)
            // Without recursive mode, cp must fail to copy any directory, empty or not,
            // whether the destination is an existing directory or a new path,
            // and must not change the file system when it fails
            assertFailsWith<IllegalArgumentException> { fsm.cp(Path("/empty-dir"), Path("/dst-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())
            assertFailsWith<IllegalArgumentException> { fsm.cp(Path("/empty-dir"), Path("/new-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())
            assertFailsWith<IllegalArgumentException> { fsm.cp(Path("/nonempty-dir"), Path("/dst-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())
            assertFailsWith<IllegalArgumentException> { fsm.cp(Path("/nonempty-dir"), Path("/new-dir")) }
            assertEquals(rootAtT1, fsm.getRoot())
        }
    }

    @Test
    fun `recursive cp copies directories`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val t3 = t2 + 1.minutes
        val t4 = t3 + 1.minutes
        val t5 = t4 + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/empty-dir"))
            fsm.mkdir(Path("/dst-dir"))
            fsm.mkdir(Path("/nonempty-dir"))
            fsm.write(Path("/nonempty-dir/a.txt"), content)
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, content.size,
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "dst-dir" to { isDir(t1, t1, 0, 0) },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t2)
            // Copying an empty directory into an existing directory keeps its name.
            // The copy is a new directory, created now, and the original is left alone.
            fsm.cpRecursive(Path("/empty-dir"), Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 1, content.size,
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t2, 0, 0,
                            "empty-dir" to { isDir(t2, t2, 0, 0) },
                        )
                    },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t3)
            // Copying an empty directory to a path that doesn't exist creates a copy under the new name
            fsm.cpRecursive(Path("/empty-dir"), Path("/new-empty-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t3, 1, content.size,
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "new-empty-dir" to { isDir(t3, t3, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t2, 0, 0,
                            "empty-dir" to { isDir(t2, t2, 0, 0) },
                        )
                    },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t4)
            // Copying a non-empty directory into an existing directory keeps its name.
            // Every node in the copy is new, so all of them are created now, and the originals are left alone.
            fsm.cpRecursive(Path("/nonempty-dir"), Path("/dst-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t3, 2, content.size * 2,
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "new-empty-dir" to { isDir(t3, t3, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t4, 1, content.size,
                            "empty-dir" to { isDir(t2, t2, 0, 0) },
                            "nonempty-dir" to {
                                isDir(t4, t4, 1, content.size,
                                    "a.txt" to { isFile(t4, t4, content, content.size) },
                                )
                            },
                        )
                    },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                )
            }

            delayUntil(t5)
            // Copying a non-empty directory to a path that doesn't exist creates a copy under the new name
            fsm.cpRecursive(Path("/nonempty-dir"), Path("/new-nonempty-dir"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t5, 3, content.size * 3,
                    "empty-dir" to { isDir(t1, t1, 0, 0) },
                    "new-empty-dir" to { isDir(t3, t3, 0, 0) },
                    "dst-dir" to {
                        isDir(t1, t4, 1, content.size,
                            "empty-dir" to { isDir(t2, t2, 0, 0) },
                            "nonempty-dir" to {
                                isDir(t4, t4, 1, content.size,
                                    "a.txt" to { isFile(t4, t4, content, content.size) },
                                )
                            },
                        )
                    },
                    "nonempty-dir" to {
                        isDir(t1, t1, 1, content.size,
                            "a.txt" to { isFile(t1, t1, content, content.size) },
                        )
                    },
                    "new-nonempty-dir" to {
                        isDir(t5, t5, 1, content.size,
                            "a.txt" to { isFile(t5, t5, content, content.size) },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `recursive cp can still copy a file`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.write(Path("/a.txt"), content)

            delayUntil(t2)
            fsm.cpRecursive(Path("/a.txt"), Path("/b.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t2, 2, content.size * 2,
                    "a.txt" to { isFile(t1, t1, content, content.size) },
                    "b.txt" to { isFile(t2, t2, content, content.size) },
                )
            }
        }
    }

    @Test
    fun `glob with a literal file path matches that file or nothing`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.write(Path("/foo/a.txt"), content)
            fsm.touch(Path("/foo/b.txt"))

            // A literal path that exists matches exactly that file
            assertMap(fsm.glob(Path("/foo/a.txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
            )

            // A literal path that doesn't exist matches nothing
            assert(fsm.glob(Path("/foo/c.txt")).isEmpty())
        }
    }

    @Test
    fun `glob component with a star matches any char sequence`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.write(Path("/foo/a.txt"), content)
            fsm.touch(Path("/foo/bcd.txt"))
            fsm.mkdir(Path("/bar"))
            fsm.touch(Path("/bar/a.txt"))

            assertMap(fsm.glob(Path("/foo/*.txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
                Path("/foo/bcd.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assertMap(fsm.glob(Path("/*/a.txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
                Path("/bar/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )
        }
    }

    @Test
    fun `glob component with question marks matches that many chars`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.write(Path("/foo/a.txt"), content)
            fsm.touch(Path("/foo/bcd.txt"))
            fsm.mkdir(Path("/bar"))
            fsm.touch(Path("/bar/a.txt"))

            assertMap(fsm.glob(Path("/foo/?.txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
            )

            assertMap(fsm.glob(Path("/foo/???.txt")),
                Path("/foo/bcd.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assert(fsm.glob(Path("/?/a.txt")).isEmpty())

            assertMap(fsm.glob(Path("/???/a.txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
                Path("/bar/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )
        }
    }

    @Test
    fun `glob component with a character group matches any listed char`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.write(Path("/foo/a.txt"), content)
            fsm.touch(Path("/foo/b.txt"))
            fsm.touch(Path("/foo/d.txt"))
            fsm.mkdir(Path("/bar"))
            fsm.touch(Path("/bar/a.txt"))

            assertMap(fsm.glob(Path("/foo/[ab].txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
                Path("/foo/b.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assertMap(fsm.glob(Path("/foo/[cd].txt")),
                Path("/foo/d.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assert(fsm.glob(Path("/foo/[xyz].txt")).isEmpty())

            assertMap(fsm.glob(Path("/[fx]oo/a.txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
            )
        }
    }

    @Test
    fun `glob character group supports ranges, negation, and escapes`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val content = "hello".toByteArray()
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.write(Path("/foo/a.txt"), content)
            fsm.touch(Path("/foo/b.txt"))
            fsm.touch(Path("/foo/c.txt"))
            fsm.touch(Path("/foo/d.txt"))
            fsm.mkdir(Path("/esc"))
            fsm.touch(Path("/esc/a.txt"))
            fsm.touch(Path("/esc/].txt"))

            assertMap(fsm.glob(Path("/foo/[a-c].txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, content, content.size) } },
                Path("/foo/b.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/foo/c.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assertMap(fsm.glob(Path("/foo/[^a-c].txt")),
                Path("/foo/d.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assertMap(fsm.glob(Path("/foo/[^ab].txt")),
                Path("/foo/c.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/foo/d.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assertMap(fsm.glob(Path("/foo/[!ab].txt")),
                Path("/foo/c.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/foo/d.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assertMap(fsm.glob(Path("/esc/[\\]].txt")),
                Path("/esc/].txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assertMap(fsm.glob(Path("/esc/[\\]a].txt")),
                Path("/esc/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/esc/].txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )
        }
    }

    @Test
    fun `glob double star matches any number of directories`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdirP(Path("/foo/bar/baz"))
            fsm.mkdir(Path("/other"))
            fsm.touch(Path("/a.txt"))
            fsm.touch(Path("/foo/a.txt"))
            fsm.touch(Path("/foo/b.txt"))
            fsm.touch(Path("/foo/bar/a.txt"))
            fsm.touch(Path("/foo/bar/baz/c.txt"))
            fsm.touch(Path("/other/a.txt"))

            // ** can match zero directories, so a.txt directly under the root matches too
            assertMap(fsm.glob(Path("/**/a.txt")),
                Path("/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/foo/bar/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/other/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            // ** only searches below the directory before it
            assertMap(fsm.glob(Path("/foo/**/a.txt")),
                Path("/foo/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
                Path("/foo/bar/a.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            // Repeated ** can match the same path in several ways, but it's only reported once
            assertMap(fsm.glob(Path("/**/**/c.txt")),
                Path("/foo/bar/baz/c.txt") to { checkFileNode(it) { isFile(t1, t1, null, 0) } },
            )

            assert(fsm.glob(Path("/**/missing.txt")).isEmpty())

            // A trailing ** matches the directory itself and everything below it, files and directories alike
            assertMap(fsm.glob(Path("/foo/**")),
                Path("/foo") to { assertIs<Directory>(it) },
                Path("/foo/a.txt") to { assertIs<File>(it) },
                Path("/foo/b.txt") to { assertIs<File>(it) },
                Path("/foo/bar") to { assertIs<Directory>(it) },
                Path("/foo/bar/a.txt") to { assertIs<File>(it) },
                Path("/foo/bar/baz") to { assertIs<Directory>(it) },
                Path("/foo/bar/baz/c.txt") to { assertIs<File>(it) },
            )
        }
    }

    @Test
    fun `glob rejects malformed patterns`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            fsm.mkdir(Path("/foo"))

            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/[abc")) }
            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/[^abc")) }
            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/[")) }
            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/[]")) }
            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/[^]")) }
            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/a\\")) }
            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/[ab\\")) }
            assertFailsWith<IllegalArgumentException> { fsm.glob(Path("/foo/ab**cd")) }
        }
    }
}

private fun <K, V> assertMap(actualMap: Map<K, V>, vararg entryAssertions: Pair<K, (V) -> Unit>) {
    for ((k, vAssertion) in entryAssertions) {
        assertContains(actualMap, k)
        vAssertion(actualMap.getValue(k))
    }
    assertEquals(entryAssertions.size, actualMap.size)
}
