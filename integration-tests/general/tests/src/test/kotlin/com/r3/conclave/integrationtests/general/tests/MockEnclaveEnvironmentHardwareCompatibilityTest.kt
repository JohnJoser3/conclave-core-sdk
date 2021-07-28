package com.r3.conclave.integrationtests.general.tests

import com.google.common.collect.Sets
import com.r3.conclave.common.EnclaveMode
import com.r3.conclave.integrationtests.general.enclave.SecretKeyEnclave1
import com.r3.conclave.integrationtests.general.enclave.SecretKeyEnclave2
import com.r3.conclave.common.MockConfiguration
import com.r3.conclave.common.internal.*
import com.r3.conclave.common.OpaqueBytes
import kotlin.random.Random
import com.r3.conclave.host.EnclaveHost
import com.r3.conclave.host.EnclaveLoadException
import com.r3.conclave.host.internal.InternalsKt.createHost
import com.r3.conclave.host.internal.InternalsKt.createMockHost
import com.r3.conclave.integrationtests.general.common.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.IllegalStateException
import java.nio.file.Files.copy
import java.nio.file.StandardCopyOption

/**
 * Tests to make sure secret keys produced by [MockEnclaveEnvironment.getSecretKey] behave similarly to ones produced
 * in hardware mode.
 */

class MockEnclaveEnvironmentHardwareCompatibilityTest {
    companion object {

        // A list of all the SecretKeySpecs that we want to test for, created by a cartesian product of the various
        // key request parameters we're interested in.
        private val secretKeySpecs: List<SecretKeySpec> = Sets.cartesianProduct(
            // Create keys across two different enclaves, ...
            setOf(EnclaveClass(SecretKeyEnclave1::class.java), EnclaveClass(SecretKeyEnclave2::class.java)),
            /* Note: the following two sets (IsvProdId and IsvSvn) were containing two values each.
                Both IsvProdId and IsvSvn are defining the identity of the enclaves.
                This was not a problem, as this test was used with Avian and it was possible to dynamically create
                enclaves with different Prod Ids and IsvSvn values.
                But with GraalVM the enclaves can't be built dynamically, so for the moment we only set one
                value for each set, so that we only test one combination, which is matching the combination of the
                enclaves.
            */
            setOf(EnclaveIsvProdId(1)),
            setOf(EnclaveIsvSvn(1)),
            // Create either REPORT or SEAL keys. The other key types are not relevant.
            setOf(KeyNameField(KeyName.REPORT), KeyNameField(KeyName.SEAL)),
            // Create keys with all 8 possible policy combinations by using MRENCLAVE, MRSIGNER and NOISVPRODID.
            // The other key policies are not relevant as we don't enable KSS.
            Sets.powerSet(setOf(KeyPolicy.MRENCLAVE, KeyPolicy.MRSIGNER, KeyPolicy.NOISVPRODID)).map(::KeyPolicyField)
                .toSet(),
            // Create keys with no ISVSVN, ISVSVN set to the enclave's and with values on either side.
            setOf(BlankField(SgxKeyRequest::isvSvn), IsvSvnFieldDelta(-1), IsvSvnFieldDelta(0), IsvSvnFieldDelta(1)),
            // Create keys with no CPUSVN, CPUSVN set to the current value and a random one.
            setOf(
                BlankField(SgxKeyRequest::cpuSvn),
                CpuSvnField(null),
                CpuSvnField(OpaqueBytes(Random.nextBytes(SgxCpuSvn.INSTANCE.size)))
            ),
            // Create keys with no key ID and with a random one.
            setOf(BlankField(SgxKeyRequest::keyId), KeyIdField(OpaqueBytes(Random.nextBytes(SgxKeyId.INSTANCE.size))))
        ).map(::SecretKeySpec)
    }

    private val nativeEnclaves = HashMap<EnclaveSpec, EnclaveHost>()
    private val mockEnclaves = HashMap<EnclaveSpec, EnclaveHost>()

    @BeforeEach
    fun setup() {
        //  We want this test to run only in DEBUG mode
        assumeTrue(EnclaveMode.DEBUG.name.toLowerCase() == System.getProperty("enclaveMode"))
    }

    @AfterEach
    fun cleanUp() {
        nativeEnclaves.values.forEach(EnclaveHost::close)
    }

    @Test
    fun `mock secret keys have the same uniqueness as hardware secret keys`() {
        val nativeUniqueness = KeyUniquenessContainer(::getNativeHost)
        val mockUniqueness = KeyUniquenessContainer(::getMockHost)

        for (spec in secretKeySpecs) {
            nativeUniqueness.queryAndDetermineKeyUniqueness(spec)
            mockUniqueness.queryAndDetermineKeyUniqueness(spec)
        }

        println("Key requests producing the same key:")
        println("====================================")
        nativeUniqueness.keyToSameKeyGroup.values.forEach { group ->
            group.forEach(::println)
            println()
        }
        println()

        println("Key requests producing errors:")
        println("==============================")
        nativeUniqueness.errorKeyRequests.forEach { keyRequestSpec, message ->
            println("$keyRequestSpec: $message")
        }
        println()

        // If native throws then mock must throw with the same message.
        nativeUniqueness.errorKeyRequests.forEach { keyRequestSpec, nativeError ->
            val mockError = mockUniqueness.errorKeyRequests[keyRequestSpec]
            assertNotNull(mockError) { "$keyRequestSpec returned a key but should have returned error '$nativeError'" }
            assertEquals(nativeError, mockError, keyRequestSpec::toString)
        }

        // We don't care for the native and mock keys to be the same (in fact that's not possible), but we do care that
        // the group of key requests which produce the same key is the same across native and mock. This means, for
        // example, if a native key request produces a unique key then so must the mock key request, and if a native key
        // request produces a non-unique key then the set of key requests which produce that same key is the same across
        // native and mock.
        for ((keyRequestSpec, mockGroup) in mockUniqueness.keyRequestToSameKeyGroup) {
            val nativeGroup = nativeUniqueness.keyRequestToSameKeyGroup.getValue(keyRequestSpec)
            assertThat(mockGroup).describedAs(keyRequestSpec.toString()).isEqualTo(nativeGroup)
        }
    }

    private fun loadNativeHostFromFile(enclaveSpec: EnclaveSpec) : EnclaveHost {
        val enclaveClassName = enclaveSpec.enclaveClass.canonicalName
        // Look for an SGX enclave image.
        val enclaveMode = EnclaveMode.DEBUG
        val resourceName = "/${enclaveClassName.replace('.', '/')}-${enclaveMode.name.toLowerCase()}.signed.so"
        val url = EnclaveHost::class.java.getResource(resourceName)
        val found =  Pair(url, enclaveMode)
        val stream = found.first.openStream()

        val enclaveFile = try {
            createTempFile(enclaveClassName, "signed.so")
        } catch (e: Exception) {
            throw EnclaveLoadException("Unable to load enclave", e)
        }
        try {
            stream.use { copy(it, enclaveFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return createHost(enclaveMode, enclaveFile.toPath(), enclaveClassName, true)
        } catch (e: Exception) {
            enclaveFile.delete()
            throw if (e is EnclaveLoadException) e else EnclaveLoadException("Unable to load enclave", e)
        }
    }

    private fun getNativeHost(enclaveSpec: EnclaveSpec): EnclaveHost {
        return nativeEnclaves.computeIfAbsent(enclaveSpec) {
            val host = loadNativeHostFromFile(enclaveSpec)

            val attestationParameters = when (host.enclaveMode) {
                EnclaveMode.RELEASE, EnclaveMode.DEBUG -> JvmTest.getHardwareAttestationParams()
                else -> throw IllegalStateException("The enclave needs to be built in Release or Debug mode")
            }

            host.start(attestationParameters, null)
            host
        }
    }

    private fun getMockHost(enclaveSpec: EnclaveSpec): EnclaveHost {
        return mockEnclaves.computeIfAbsent(enclaveSpec) {
            val mockConfiguration = MockConfiguration()
            mockConfiguration.productID = enclaveSpec.isvProdId
            mockConfiguration.revocationLevel = enclaveSpec.isvSvn - 1
            val host = createMockHost(enclaveSpec.enclaveClass, mockConfiguration)
            host.start(null, null)
            host
        }
    }

    class KeyUniquenessContainer(private val hostLookup: (EnclaveSpec) -> EnclaveHost) {
        val keyToSameKeyGroup = LinkedHashMap<OpaqueBytes, MutableList<SecretKeySpec>>()
        val keyRequestToSameKeyGroup = HashMap<SecretKeySpec, List<SecretKeySpec>>()
        val errorKeyRequests = HashMap<SecretKeySpec, String>()

        fun queryAndDetermineKeyUniqueness(spec: SecretKeySpec) {
            val result = spec.querySecretKey(hostLookup)
            assertEquals(spec.querySecretKey(hostLookup), result, "Same key request must produce same key")

            when (result) {
                is Result.Key -> {
                    assertThat(result.bytes.size).isEqualTo(SgxKey128Bit.INSTANCE.size)
                    val keyRequestGroup = keyToSameKeyGroup.computeIfAbsent(result.bytes) { ArrayList() }
                    keyRequestGroup += spec
                    keyRequestToSameKeyGroup[spec] = keyRequestGroup
                }
                is Result.Error -> {
                    errorKeyRequests[spec] = result.message
                }
            }
        }
    }
}
