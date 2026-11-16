package io.github.umbraproxy

import org.junit.Assert.*
import org.junit.Test

class ProxyConfigurationTest {
    private val secret = "00112233445566778899aabbccddeeff"

    @Test fun secretsAreRandomAndHaveFullLength() {
        val secrets = (1..100).map { ProxyConfiguration.generateSecret() }
        assertTrue(secrets.all(ProxyConfiguration::isValidSecret))
        assertEquals(secrets.size, secrets.toSet().size)
    }

    @Test fun malformedSecretsAreRejected() {
        listOf("", "0".repeat(32), "z".repeat(32), secret + "00").forEach {
            assertFalse(ProxyConfiguration.isValidSecret(it))
        }
        assertTrue(ProxyConfiguration.isValidSecret(secret.uppercase()))
    }

    @Test fun domainValidationRejectsUrlsAndHeaderInjection() {
        listOf("example.com", "kws2.example.co.uk", "my-proxy.example.com").forEach {
            assertTrue(it, ProxyConfiguration.isValidDomain(it))
        }
        listOf("", "localhost", "https://example.com", "example.com/path", "example.com:443",
            "example.com\r\nInjected: value", "-bad.example.com", "bad-.example.com", "bad..com",
            "a".repeat(64) + ".com").forEach {
            assertFalse(it, ProxyConfiguration.isValidDomain(it))
        }
    }

    @Test fun linksUseProtocolCompatibleSecretPrefixes() {
        assertEquals("dd$secret", ProxyConfiguration.secretWithPrefix(secret, false, ""))
        assertEquals("ee${secret}62696e672e636f6d", ProxyConfiguration.secretWithPrefix(secret, true, "bing.com"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fakeTlsLinkRequiresDomain() {
        ProxyConfiguration.secretWithPrefix(secret, true, "")
    }
}
