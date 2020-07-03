package com.r3.conclave.mail

import com.r3.conclave.mail.internal.Curve25519KeyPairGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalArgumentException

class MailTests {
    private companion object {
        val keyGen = Curve25519KeyPairGenerator()
        val alice = keyGen.generateKeyPair()
        val bob = keyGen.generateKeyPair()

        val message1 = "rumours gossip nonsense misinformation fake news good stuff".toByteArray()
    }

    @Test
    fun featureCombinations() {
        encryptDecrypt(withHeaders = false, withSenderAuth = false, withEnvelope = false)
        encryptDecrypt(withHeaders = true, withSenderAuth = false, withEnvelope = false)
        encryptDecrypt(withHeaders = false, withSenderAuth = true, withEnvelope = false)
        encryptDecrypt(withHeaders = true, withSenderAuth = true, withEnvelope = false)
        encryptDecrypt(withHeaders = false, withSenderAuth = false, withEnvelope = true)
        encryptDecrypt(withHeaders = true, withSenderAuth = false, withEnvelope = true)
        encryptDecrypt(withHeaders = false, withSenderAuth = true, withEnvelope = true)
        encryptDecrypt(withHeaders = true, withSenderAuth = true, withEnvelope = true)
    }

    private fun encryptDecrypt(withHeaders: Boolean, withSenderAuth: Boolean, withEnvelope: Boolean) {
        // Test the base case of sending mail from anonymous to Bob without any special headers.
        val mutableMail = MutableMail(message1, bob.public, alice.private.takeIf { withSenderAuth })
        if (withHeaders) {
            mutableMail.from = "alice"
            mutableMail.sequenceNumber = 5
            mutableMail.topic = "stuff"
        }
        if (withEnvelope) {
            mutableMail.envelope = "env".toByteArray()
        }

        fun assertEnvelope(of: EnclaveMailHeader) {
            assertEquals("alice".takeIf { withHeaders }, of.from)
            assertEquals(if (withHeaders) "stuff" else "default", of.topic)
            assertEquals(if (withHeaders) 5L else 0L, of.sequenceNumber)
            assertEquals("env".takeIf { withEnvelope }, of.envelope?.let { String(it) })
        }

        // Now check we can read the headers in the encrypted mail without needing the private key.
        val encrypted: EncryptedEnclaveMail = mutableMail.encrypt()
        // Two encryptions of the same message encrypt to different byte arrays, even if the sequence number is the
        // same. It just means that you can't tell if the same content is being sent twice in a row.
        assertFalse(encrypted.contentEquals(mutableMail.encrypt()))
        assertEnvelope(Mail.getUnauthenticatedHeader(encrypted))

        // Encrypt again.
        mutableMail.incrementSequenceNumber()
        val encrypted2 = mutableMail.encrypt()
        assertEquals(Mail.getUnauthenticatedHeader(encrypted).sequenceNumber + 1, Mail.getUnauthenticatedHeader(encrypted2).sequenceNumber)

        // Decrypt and check.
        val decrypted: EnclaveMail = Mail.decrypt(encrypted, bob.private)
        assertArrayEquals(message1, decrypted.bodyAsBytes)
        assertEnvelope(decrypted)
        assertEquals(alice.public.takeIf { withSenderAuth }, decrypted.authenticatedSender)
    }

    @Test
    fun topicChars() {
        val mutableMail = MutableMail(message1, bob.public, null)
        mutableMail.topic = "valid-topic"
        assertThrows<IllegalArgumentException> { mutableMail.topic = "no whitespace allowed" }
        assertThrows<IllegalArgumentException> { mutableMail.topic = "!!!" }
        assertThrows<IllegalArgumentException> { mutableMail.topic = "😂" }
        // Disallow dots as they are often meaningful in queue names.
        assertThrows<IllegalArgumentException> { mutableMail.topic = "1234.5678" }
    }

    @Test
    @Disabled("not implemented yet")
    fun sizePadding() {
        val mutableMail = MutableMail(message1, bob.public, minSize = 10 * 1024)
        val encrypted = mutableMail.encrypt()
        assertTrue(encrypted.size >= 10 * 1024)
    }
}