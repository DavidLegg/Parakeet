package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delayUntil
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.getRoot
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.mkdir
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.mkdirP
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.rm
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.touch
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
    fun `rm removes individual files`() {
        val start = Instant.parse("2000-01-01T00:00:00Z")
        val t1 = start + 1.minutes
        val t2 = t1 + 1.minutes
        runUnitTest(start, { FileSystemModel("fs") }) { fsm ->
            delayUntil(t1)
            fsm.mkdir(Path("/foo"))
            fsm.touch(Path("/foo/a.txt"))
            fsm.touch(Path("/foo/b.txt"))
            fsm.mkdir(Path("/bar"))
            fsm.mkdir(Path("/baz"))
            fsm.touch(Path("/baz/x.txt"))
            checkFileNode(fsm.getRoot()) {
                isDir(start, t1, 3, 0,
                    "foo" to {
                        isDir(t1, t1, 2, 0,
                            "a.txt" to { isFile(t1, t1, sizeBytes = 0) },
                            "b.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                    "bar" to { isDir(t1, t1, 0, 0) },
                    "baz" to {
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
                    "bar" to { isDir(t1, t1, 0, 0) },
                    "baz" to {
                        isDir(t1, t1, 1, 0,
                            "x.txt" to { isFile(t1, t1, sizeBytes = 0) },
                        )
                    },
                )
            }
            // rm only removes files, so it must fail on both empty and non-empty directories
            // and it must not change the file system when it fails
            assertFailsWith<IllegalArgumentException> { fsm.rm(Path("/bar")) }
            assertEquals(rootAtT2, fsm.getRoot())
            assertFailsWith<IllegalArgumentException> { fsm.rm(Path("/baz")) }
            assertEquals(rootAtT2, fsm.getRoot())
            // In a slight departure from shell `rm` semantics, our rm quietly tolerates a missing file.
            // This allows concurrent tasks to both `rm` a file, without error. This matches the intent of "just delete the file".
            fsm.rm(Path("/foo/a.txt"))
            assertEquals(rootAtT2, fsm.getRoot())
        }
    }
}