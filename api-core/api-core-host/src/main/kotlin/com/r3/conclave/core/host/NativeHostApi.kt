package com.r3.conclave.core.host

import com.r3.conclave.common.internal.ByteCursor
import com.r3.conclave.common.internal.Cursor
import com.r3.conclave.common.internal.SgxMetadata
import com.r3.conclave.core.common.Handler
import com.r3.conclave.core.host.internal.*
import java.io.File

class NativeHostApi(val loadMode: EnclaveLoadMode) {
    val isDebug = when (loadMode) {
        EnclaveLoadMode.RELEASE -> false
        else -> true
    }

    init {
        NativeLoader.loadHostLibraries(loadMode)
    }

    fun <CONNECTION> createEnclave(handler: Handler<CONNECTION>, enclaveFile: File, enclaveClassName: String): EnclaveHandle<CONNECTION> {
        val enclaveId = Native.createEnclave(enclaveFile.absolutePath, isDebug)
        return NativeEnclaveHandle(this, enclaveId, handler, enclaveClassName)
    }

    fun destroyEnclave(enclaveId: EnclaveId) {
        Native.destroyEnclave(enclaveId)
    }

    fun readMetadata(enclaveFile: File): ByteCursor<SgxMetadata> {
        val cursor = Cursor.allocate(SgxMetadata)
        Native.getMetadata(enclaveFile.absolutePath, cursor.getBuffer().array())
        return cursor
    }
}