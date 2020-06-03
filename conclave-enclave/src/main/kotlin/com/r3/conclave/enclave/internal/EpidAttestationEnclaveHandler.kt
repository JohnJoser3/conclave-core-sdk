package com.r3.conclave.enclave.internal

import com.r3.conclave.common.internal.*
import com.r3.conclave.common.internal.handler.Handler
import com.r3.conclave.common.internal.handler.Sender
import java.nio.ByteBuffer
import java.util.function.Consumer

/**
 * The enclave side of the EPID attestation protocol. It receives report requests from the host, containing the target
 * Quoting Enclave's information, and sends back a report.
 */
abstract class EpidAttestationEnclaveHandler(val api: EnclaveApi) : Handler<Sender> {
    abstract val reportData: ByteCursor<SgxReportData>

    override fun connect(upstream: Sender): Sender = upstream

    override fun onReceive(connection: Sender, input: ByteBuffer) {
        val quotingEnclaveTargetInfo = input.getRemainingBytes()
        val report = ByteArray(SgxReport.size())
        api.createReport(quotingEnclaveTargetInfo, reportData.getBuffer().array(), report)
        connection.send(report.size, Consumer { buffer ->
            buffer.put(report)
        })
    }
}