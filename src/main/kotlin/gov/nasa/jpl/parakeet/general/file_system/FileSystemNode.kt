package gov.nasa.jpl.parakeet.general.file_system

import java.security.MessageDigest
import kotlin.time.Instant

sealed interface FileSystemNode {
    val name: String
    val createdTime: Instant
    val modifiedTime: Instant
    val sizeBytes: Int
    /** Collision-resistant hash of node's contents. Do not mutate! */
    val digest: ByteArray

    data class Directory(
        override val name: String,
        override val createdTime: Instant,
        override val modifiedTime: Instant = createdTime,
        val children: Map<String, FileSystemNode> = emptyMap(),
    ) : FileSystemNode {
        override val sizeBytes: Int = children.values.sumOf { it.sizeBytes }
        val numFiles: Int = children.values.sumOf {
            when (it) {
                is Directory -> it.numFiles
                is File, is Corrupted -> 1
            }
        }
        override val digest: ByteArray = computeDigest {
            update(name.toByteArray(Charsets.UTF_8))
            update(createdTime)
            update(modifiedTime)
            for ((name, child) in children) {
                update(name)
                update(child.digest)
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is FileSystemNode && digest.contentEquals(other.digest))
        override fun hashCode(): Int = digest.contentHashCode()
    }

    data class File(
        override val name: String,
        override val createdTime: Instant,
        override val modifiedTime: Instant = createdTime,
        val content: ByteArray? = null,
        override val sizeBytes: Int = content?.size ?: 0,
    ) : FileSystemNode {
        override val digest: ByteArray = computeDigest {
            update(name)
            update(createdTime)
            update(modifiedTime)
            update(sizeBytes)
            content?.let(::update)
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is FileSystemNode && digest.contentEquals(other.digest))
        override fun hashCode(): Int = digest.contentHashCode()
    }

    data class Corrupted(
        override val name: String,
        override val createdTime: Instant,
        override val modifiedTime: Instant,
        override val sizeBytes: Int,
        val original: FileSystemNode?,
        val exception: Throwable,
    ) : FileSystemNode {
        constructor(
            original: FileSystemNode,
            exception: Throwable,
        ) : this(
            original.name,
            original.createdTime,
            original.modifiedTime,
            original.sizeBytes,
            original,
            exception,
        )

        constructor(
            name: String,
            createdTime: Instant,
            modifiedTime: Instant = createdTime,
            sizeBytes: Int = 0,
            exception: Throwable,
        ) : this(
            name,
            createdTime,
            modifiedTime,
            sizeBytes,
            null,
            exception,
        )

        // Don't include the exception in the digest. It's not valuable for comparison.
        override val digest: ByteArray = computeDigest {
            update(name)
            update(createdTime)
            update(modifiedTime)
            update(sizeBytes)
            original?.let { update(it.digest) }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is FileSystemNode && digest.contentEquals(other.digest))
        override fun hashCode(): Int = digest.contentHashCode()
    }
}

private fun computeDigest(block: MessageDigest.() -> Unit): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    block(md)
    return md.digest()
}

// Private helpers for updating a message digest with all bytes of certain data types without constructing byte arrays

private fun MessageDigest.update(int: Int) {
    update(int.toByte())
    update((int ushr 8).toByte())
    update((int ushr 16).toByte())
    update((int ushr 24).toByte())
}

private fun MessageDigest.update(long: Long) {
    update(long.toInt())
    update((long ushr 32).toInt())
}

private fun MessageDigest.update(instant: Instant) {
    update(instant.epochSeconds)
    update(instant.nanosecondsOfSecond)
}

private fun MessageDigest.update(string: String) {
    update(string.toByteArray(Charsets.UTF_8))
}
