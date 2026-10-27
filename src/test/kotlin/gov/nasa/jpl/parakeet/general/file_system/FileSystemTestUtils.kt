package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.get
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

object FileSystemTestUtils {
    fun checkFileNode(
        subject: FileSystemNode?,
        block: FileSystemNodeChecker.() -> Unit,
    ) = checkFileNode(subject, block, Path("/"))

    private fun checkFileNode(
        subject: FileSystemNode?,
        block: FileSystemNodeChecker.() -> Unit,
        location: Path,
    ) {
        block(object : FileSystemNodeChecker {
            override fun isDir(
                createdTime: Instant?,
                modifiedTime: Instant?,
                numFiles: Int?,
                sizeBytes: Int?,
                vararg children: Pair<String, FileSystemNodeChecker.() -> Unit>
            ) = assertHere {
                assertIs<Directory>(subject)
                location.takeIf { it != Path("/") }?.name?.let { assertEquals(it, subject.name) }
                createdTime?.let { assertEquals(it, subject.createdTime) }
                modifiedTime?.let { assertEquals(it, subject.modifiedTime) }
                numFiles?.let { assertEquals(it, subject.numFiles) }
                sizeBytes?.let { assertEquals(it, subject.sizeBytes) }
                assertEquals(children.size, subject.children.size)
                for ((childName, childChecker) in children) {
                    checkFileNode(subject[childName], childChecker, location / childName)
                }
            }

            override fun isFile(
                createdTime: Instant?,
                modifiedTime: Instant?,
                content: ByteArray?,
                sizeBytes: Int?
            ) = assertHere {
                assertIs<File>(subject)
                location.takeIf { it != Path("/") }?.name?.let { assertEquals(it, subject.name) }
                createdTime?.let { assertEquals(it, subject.createdTime) }
                modifiedTime?.let { assertEquals(it, subject.modifiedTime) }
                content?.let { assertContentEquals(it, subject.content) }
                sizeBytes?.let { assertEquals(it, subject.sizeBytes) }
            }

            override fun isCorrupted(
                createdTime: Instant?,
                modifiedTime: Instant?,
                sizeBytes: Int?,
                checkException: (Throwable) -> Unit,
                checkOriginal: FileSystemNodeChecker.() -> Unit
            ) {
                assertIs<Corrupted>(subject)
                location.takeIf { it != Path("/") }?.name?.let { assertEquals(it, subject.name) }
                createdTime?.let { assertEquals(it, subject.createdTime) }
                modifiedTime?.let { assertEquals(it, subject.modifiedTime) }
                sizeBytes?.let { assertEquals(it, subject.sizeBytes) }
                checkException(subject.exception)
                checkFileNode(subject.original, checkOriginal, location.resolveSibling(location.name + " (original)"))
            }

            override fun isNull() = assertHere { require(subject == null) }

            private inline fun assertHere(block: () -> Unit) = try {
                block()
            } catch (e: AssertionError) {
                throw AssertionError("Assertion failed at $location", e)
            } catch (e: Throwable) {
                throw RuntimeException("Unexpected exception at $location", e)
            }
        })
    }

    interface FileSystemNodeChecker {
        fun isDir(
            createdTime: Instant? = null,
            modifiedTime: Instant? = null,
            numFiles: Int? = null,
            sizeBytes: Int? = null,
            vararg children: Pair<String, FileSystemNodeChecker.() -> Unit>,
        )

        fun isFile(
            createdTime: Instant? = null,
            modifiedTime: Instant? = null,
            content: ByteArray? = null,
            sizeBytes: Int? = null,
        )

        fun isCorrupted(
            createdTime: Instant? = null,
            modifiedTime: Instant? = null,
            sizeBytes: Int? = null,
            checkException: (Throwable) -> Unit = {},
            checkOriginal: FileSystemNodeChecker.() -> Unit = {},
        )

        fun isNull()
    }
}