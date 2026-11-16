package io.github.umbraproxy

import java.security.SecureRandom

/** Validation and link formatting shared by the UI and service entry points. */
object ProxyConfiguration {
    fun isValidSecret(secret: String): Boolean =
        secret.matches(Regex("[0-9a-fA-F]{32}")) && secret.any { it != '0' }

    fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isValidDomain(domain: String): Boolean =
        domain.length in 1..253 && domain.contains('.') && domain.split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() && label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }

    fun secretWithPrefix(secret: String, fakeTls: Boolean, domain: String): String {
        require(isValidSecret(secret)) { "Invalid proxy secret" }
        if (!fakeTls) return "dd$secret"
        require(isValidDomain(domain)) { "Invalid FakeTLS domain" }
        return "ee$secret" + domain.toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
