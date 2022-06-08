package com.r3.conclave.common

import com.r3.conclave.common.internal.EnclaveInstanceInfoImpl
import com.r3.conclave.common.internal.SignatureSchemeEdDSA
import com.r3.conclave.common.internal.attestation.Attestation
import com.r3.conclave.mail.*
import com.r3.conclave.utilities.internal.getIntLengthPrefixBytes
import com.r3.conclave.utilities.internal.getSlice
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * Contains serializable information about an instantiated enclave running on a
 * specific machine, with the measurement and instance signing key verified by
 * remote attestation. The remote attestation infrastructure backing all trusted
 * computing schemes is what gives you confidence that the data in this object is
 * correct and can be trusted, as long as [securityInfo] and [enclaveInfo]
 * match what you expect.
 *
 * An [EnclaveInstanceInfo] should be fetched from the host via some app specific
 * mechanism, such as via an HTTP request, a directory service lookup, shared file
 * etc.
 */
interface EnclaveInstanceInfo {
    /** Contains information about the enclave code that was loaded. */
    val enclaveInfo: EnclaveInfo

    /**
     * Returns the enclave's public encryption key.
     *
     * For creating mail targeted to this enclave use a [PostOffice] from [createPostOffice].
     */
    val encryptionKey: PublicKey

    /**
     * A key used by the enclave to digitally sign static data structures.
     *
     * This is not the same as the enclave code signing key, which just links
     * the enclave code to its author.
     */
    val dataSigningKey: PublicKey

    /**
     * Returns a [Signature] object pre-initialised with [dataSigningKey], ready for the verification of digitial signatures
     * generated by the enclave.
     */
    fun verifier(): Signature

    /**
     * Exposes how secure the remote enclave is currently considered to be.
     */
    val securityInfo: EnclaveSecurityInfo

    /** Serializes this object to a custom format and returns the byte array. */
    fun serialize(): ByteArray

    /**
     * Returns a new [PostOffice] instance for encrypting mail to this target enclave on the given topic.
     *
     * Each mail created by this post office will be authenticated with the given private key, and will act as the client's
     * authenticated identity to the enclave (see [EnclaveMail.authenticatedSender]). Typically only one sender key is
     * required per client (a new one can be created using [Curve25519PrivateKey.random]).
     *
     * It's very important that related mail are created from the same post office instance, i.e. having the same topic and
     * sender key. This is so the post office can apply an increasing sequence number to each mail, which the target
     * enclave will use to make sure they are received in order and that none have been dropped (see
     * [EnclaveMailHeader.sequenceNumber]).
     *
     * For a different stream of mail create another post office with a different topic.
     */
    fun createPostOffice(senderPrivateKey: PrivateKey, topic: String): PostOffice

    /**
     * Returns a new [PostOffice] instance for encrypting mail to this target enclave on the "default" topic.
     *
     * A new sender private key will be used (which can be retrieved with [PostOffice.senderPrivateKey]), and each mail
     * created by this post office will be authenticated with it and act as the client's authenticated identity to the
     * enclave (see [EnclaveMail.authenticatedSender]). Typically only one sender key is required per client.
     *
     * It's very important that related mail are created from the same post office instance, i.e. having the same topic and
     * sender key. This is so the post office can apply an increasing sequence number to each mail, which the target
     * enclave will use to make sure they are received in order and that none have been dropped (see
     * [EnclaveMailHeader.sequenceNumber]).
     *
     * For a different stream of mail create another post office with a different topic.
     */
    fun createPostOffice(): PostOffice = createPostOffice(Curve25519PrivateKey.random(), "default")

    /**
     * Suppress kotlin specific companion objects from our API documentation.
     * The public items within the object are still published in the documentation.
     * @suppress
     */
    companion object {
        private val magic = ByteBuffer.wrap("EII".toByteArray())
        private val signatureScheme = SignatureSchemeEdDSA()

        /**
         * Deserializes an [EnclaveInstanceInfo] from the given bytes.
         *
         * @throws IllegalArgumentException If the bytes are invalid.
         */
        @JvmStatic
        fun deserialize(bytes: ByteArray): EnclaveInstanceInfo = deserialize(ByteBuffer.wrap(bytes))

        /**
         * Deserializes an [EnclaveInstanceInfo] from the given byte buffer.
         *
         * @throws IllegalArgumentException If the bytes are invalid.
         */
        @JvmStatic
        fun deserialize(buffer: ByteBuffer): EnclaveInstanceInfo {
            require(buffer.remaining() > magic.capacity() && buffer.getSlice(magic.capacity()) == magic) {
                "Not EnclaveInstanceInfo bytes"
            }
            try {
                val dataSigningKey = buffer.getIntLengthPrefixBytes().let(signatureScheme::decodePublicKey)
                val encryptionKey = Curve25519PublicKey(buffer.getIntLengthPrefixBytes())
                val attestation = Attestation.getFromBuffer(buffer)
                // New fields need to be behind an availability check before being read. Use dis.available() to check if there
                // are more bytes available and only parse them if there are. If not then provide defaults.
                return EnclaveInstanceInfoImpl(dataSigningKey, encryptionKey, attestation)
            } catch (e: BufferUnderflowException) {
                throw IllegalArgumentException("Truncated EnclaveInstanceInfo bytes", e)
            } catch (e: Exception) {
                throw IllegalArgumentException("Corrupted EnclaveInstanceInfo bytes: ${e.message}", e)
            }
        }
    }
}
