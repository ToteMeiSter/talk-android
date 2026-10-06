/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import okhttp3.tls.HeldCertificate
import org.conscrypt.Conscrypt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.security.KeyStore
import java.security.Provider
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The verifier must accept a certificate only if the trust store would accept it as a chain of one certificate.
 * Run against the JDK trust manager and against Conscrypt, which is the one used on Android.
 */
@RunWith(Parameterized::class)
class SystemTrustSslCertificateVerifierTest(
    @Suppress("UNUSED_PARAMETER") name: String,
    private val provider: Provider?
) {

    private val root = HeldCertificate.Builder()
        .certificateAuthority(2)
        .commonName("Test Root")
        .build()

    private fun verifierTrusting(anchor: X509Certificate): SystemTrustSslCertificateVerifier {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("anchor", anchor)
        }
        val algorithm = TrustManagerFactory.getDefaultAlgorithm()
        val factory = if (provider != null) {
            TrustManagerFactory.getInstance(algorithm, provider)
        } else {
            TrustManagerFactory.getInstance(algorithm)
        }
        factory.init(keyStore)
        return SystemTrustSslCertificateVerifier(factory.trustManagers.filterIsInstance<X509TrustManager>().first())
    }

    private fun verify(verifier: SystemTrustSslCertificateVerifier, cert: HeldCertificate) =
        verifier.verify(cert.certificate.encoded)

    @Test
    fun trustAnchorItselfIsAccepted() {
        assertTrue(verify(verifierTrusting(root.certificate), root))
    }

    @Test
    fun certificateIssuedByAnchorIsAccepted() {
        val intermediate = HeldCertificate.Builder()
            .certificateAuthority(1)
            .commonName("Test Intermediate")
            .signedBy(root)
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName("turn.example.org")
            .addSubjectAlternativeName("turn.example.org")
            .signedBy(root)
            .build()

        val verifier = verifierTrusting(root.certificate)

        assertTrue(verify(verifier, intermediate))
        assertTrue(verify(verifier, leaf))
    }

    @Test
    fun leafBelowUntrustedIntermediateIsRejected() {
        val intermediate = HeldCertificate.Builder()
            .certificateAuthority(1)
            .commonName("Test Intermediate")
            .signedBy(root)
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName("turn.example.org")
            .addSubjectAlternativeName("turn.example.org")
            .signedBy(intermediate)
            .build()

        // the intermediate is not in the chain handed over by WebRTC for this depth, only the leaf is
        assertFalse(verify(verifierTrusting(root.certificate), leaf))
    }

    @Test
    fun foreignSelfSignedCertificateIsRejected() {
        val foreign = HeldCertificate.Builder().commonName("turn.example.org").build()

        assertFalse(verify(verifierTrusting(root.certificate), foreign))
    }

    @Test
    fun certificateWithAnchorNameButOtherKeyIsRejected() {
        val impostor = HeldCertificate.Builder()
            .certificateAuthority(2)
            .commonName("Test Root")
            .build()

        assertFalse(verify(verifierTrusting(root.certificate), impostor))
    }

    @Test
    fun expiredCertificateIsRejected() {
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder()
            .commonName("turn.example.org")
            .addSubjectAlternativeName("turn.example.org")
            .validityInterval(now - TWO_DAYS_MS, now - ONE_DAY_MS)
            .signedBy(root)
            .build()

        assertFalse(verify(verifierTrusting(root.certificate), expired))
    }

    @Test
    fun garbageBytesAreRejected() {
        val verifier = verifierTrusting(root.certificate)

        assertFalse(verifier.verify(byteArrayOf(1, 2, 3, 4)))
        assertFalse(verifier.verify(ByteArray(0)))
        assertFalse(verifier.verify(null))
    }

    @Test
    fun missingTrustManagerRejectsEverything() {
        assertFalse(SystemTrustSslCertificateVerifier(null).verify(root.certificate.encoded))
    }

    companion object {
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        private const val TWO_DAYS_MS = 2 * ONE_DAY_MS

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers(): List<Array<Any?>> =
            listOf(
                arrayOf("jdk", null),
                arrayOf("conscrypt", Conscrypt.newProvider())
            )
    }
}
