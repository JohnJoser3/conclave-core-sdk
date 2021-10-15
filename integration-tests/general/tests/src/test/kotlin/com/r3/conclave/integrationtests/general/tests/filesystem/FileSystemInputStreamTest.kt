package com.r3.conclave.integrationtests.general.tests.filesystem

import com.r3.conclave.integrationtests.general.common.tasks.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException

class FileSystemInputStreamTest : FileSystemEnclaveTest() {

    private inner class Handler(private val uid: Int, path: String) : AutoCloseable {
        init {
            callEnclave(OpenUrlFileInputStream(path, uid))
        }

        fun readByteByByte(expectedData: ByteArray) {
            for (element in expectedData) {
                val reply = callEnclave(ReadByteFromInputStream(uid))
                assertThat(reply).isEqualTo(element.toInt())
            }
            val reply = callEnclave(ReadByteFromInputStream(uid))
            assertThat(reply).isEqualTo(-1)
        }

        fun readBytes(expectedData: ByteArray) {
            val reply = callEnclave(ReadAllBytesFromInputStream(uid))
            assertThat(reply).isEqualTo(expectedData)
        }

        override fun close() {
            callEnclave(CloseInputStream(uid))
        }

        fun reset() {
            assertThatThrownBy { callEnclave(ResetInputStream(uid)) }
                    .isInstanceOf(java.lang.RuntimeException::class.java)
                    .hasCauseExactlyInstanceOf(IOException::class.java)
                    .hasMessageContaining("java.io.IOException: Resetting to invalid mark")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun fileSystemStreamReadResetReadBytes(nioApi: Boolean) {
        val path = "/filesystem.data"
        val smallFileData = byteArrayOf(1, 2, 3)
        filesWrite(path, smallFileData)
        // Create an InputStream
        Handler(uid.getAndIncrement(), path).use { inputStream ->
            // Read byte by byte
            inputStream.readByteByByte(smallFileData)
        }
        Handler(uid.getAndIncrement(), path).use { inputStream ->
            // Read all bytes at once
            inputStream.readBytes(smallFileData)
        }
        deleteFile(path, nioApi)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun fileSystemResetThrowsException(nioApi: Boolean) {
        val path = "/filesystem-reset.data"
        val smallFileData = byteArrayOf(1, 2, 3)
        filesWrite(path, smallFileData)
        // Create an InputStream
        Handler(uid.getAndIncrement(), path).use { inputStream ->
            inputStream.reset()
        }
        deleteFile(path, nioApi)
    }
}
