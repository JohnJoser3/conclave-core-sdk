package com.r3.conclave.jvmtester.djvm.asserters

import com.r3.conclave.jvmtester.djvm.proto.StringList
import com.r3.conclave.jvmtester.api.TestAsserter
import org.assertj.core.api.Assertions.assertThat

class X500Tests {

    class TestCreateX500Principal : TestAsserter {
        override fun assertResult(testResult: ByteArray) {
            val result = String(testResult)
            assertThat(result).isEqualTo("cn=example,o=corda,c=gb")
        }
    }

    class TestX500PrincipalToX500Name : TestAsserter {
        override fun assertResult(testResult: ByteArray) {
            val result = StringList.parseFrom(testResult).valuesList
            assertThat(result).isEqualTo(listOf("c=gb", "cn=example", "o=corda"))
        }
    }

    class TestX500NameToX500Principal : TestAsserter {
        override fun assertResult(testResult: ByteArray) {
            val result = String(testResult)
            assertThat(result).isEqualTo("cn=example,o=corda,c=gb")
        }
    }
}