package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delayUntil
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.cp
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.getRoot
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
import gov.nasa.jpl.parakeet.general.file_system.FileSystemTestUtils.checkFileNode
import gov.nasa.jpl.parakeet.general.testing.UnitTesting.runUnitTest
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}