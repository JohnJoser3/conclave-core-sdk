package com.r3.sgx.core.common.attestation

import com.r3.sgx.core.common.ByteCursor
import com.r3.sgx.core.common.Cursor
import com.r3.sgx.core.common.SgxQuote
import com.r3.sgx.core.common.SgxReportData
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Authenticate a [PublicKey] generated by an SGX enclave against the content of an attested [SgxQuote]
 * by checking that a digest computed from the key in encoded form matches the content of the [reportData]
 * field of the quote.
 *
 * @property attestedQuote A trusted [SgxQuote] (e.g., a raw quote validated by [EpidAttestationVerification]).
 * @property quotedKeyDigest Name of digest function used to generate the quoted report data from the key, by
 * default "SHA-512" is used.
 */
class PublicKeyAttester(
        val attestedQuote: AttestedOutput<ByteCursor<SgxQuote>>,
        val quotedKeyDigest: String
) {
    private val quoteReader = SgxQuoteReader(attestedQuote.data)

    constructor(attestedQuote: AttestedOutput<ByteCursor<SgxQuote>>)
            : this(attestedQuote, DEFAULT_KEY_DIGEST)

    init {
        // Check message digest match reportData field size
        require(getDigest().digestLength == SgxReportData.size)
    }

    /**
     * Check public key digest matches the value reported in [attestedQuote].
     *
     * @return an [AttestedOutput] wrapping the input key, if the check passes.
     * @throws [SecurityException] if the check fails.
     */
    fun attest(data: PublicKey): AttestedOutput<PublicKey> {
        val digestedKey = getDigest().digest(data.encoded)
        if (ByteBuffer.wrap(digestedKey).compareTo(quoteReader.reportData) != 0) {
            throw SecurityException("Failed to attest enclave measurement")
        }
        return AttestedPublicKey(data = data, source = attestedQuote.source)
    }

    private fun getDigest(): MessageDigest {
        return MessageDigest.getInstance(quotedKeyDigest)
    }

    private class AttestedPublicKey(
            override val data: PublicKey,
            override val source: Measurement
    ): AttestedOutput<PublicKey>

    companion object {

        const val DEFAULT_KEY_DIGEST = "SHA-512"

    }
}