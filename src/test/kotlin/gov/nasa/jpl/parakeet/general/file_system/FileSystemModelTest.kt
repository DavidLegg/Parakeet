package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delayUntil
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.getRoot
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.mkdir
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.mkdirP
import gov.nasa.jpl.parakeet.general.file_system.FileSystemTestUtils.checkFileNode
import gov.nasa.jpl.parakeet.general.testing.UnitTesting.runUnitTest
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
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
}