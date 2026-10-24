/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import androidx.annotation.VisibleForTesting
import com.nextcloud.talk.logger.AppLog as Log
import org.webrtc.SSLCertificateVerifier
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Accepts the TURNS certificate which the built-in WebRTC roots (no ISRG/Let's Encrypt) reject.
 *
 * WebRTC calls this only after its own check failed and without the error type. What it passes depends on the version:
 * the CA at the failing depth (libwebrtc 132) or only the leaf (libwebrtc 155, `VerifyChain`). The host name is
 * still checked by the native code (`SSLPostConnectionCheck`), the verifier only replaces the trust decision.
 *
 *  - A CA (keyCertSign, serverAuth or no EKU) is accepted if the trust anchors of the app's network security config
 *    (system and user CAs; a `domain-config` there makes the check fail) trust it alone. A non-CA, or a CA without
 *    keyCertSign or serverAuth, would clear errors of another kind and is not taken as a CA.
 *  - Any other certificate is a leaf. It needs serverAuth (or no EKU) and a chain `leaf -> intermediates -> anchor`,
 *    built from [intermediates] by issuer name and signature and checked by the same trust manager (validity,
 *    signatures, anchor). The server sends the intermediates, but the native code hands over only the leaf.
 *
 * [intermediates] is the bundle `turns-tls/lets-encrypt-intermediates.pem` (public certificates from
 * https://letsencrypt.org/certificates/): YE1-3 and YR1-3 with Root YE (cross-signed by ISRG Root X2) and Root YR
 * (cross-signed by ISRG Root X1), ISRG Root X2 cross-signed by ISRG Root X1, E7-E9 (also cross-signed) and R12-R14.
 * Chains end at ISRG Root X1, as Android 12 has no ISRG Root X2.
 */
class SystemTrustSslCertificateVerifier(
    private val trustManager: X509TrustManager?,
    intermediates: () -> List<X509Certificate> = ::bundledIntermediates
) : SSLCertificateVerifier {

    private val intermediates: List<X509Certificate> by lazy { intermediates() }

    @Suppress("TooGenericExceptionCaught")
    override fun verify(certificate: ByteArray?): Boolean {
        if (certificate == null || trustManager == null) {
            return false
        }
        return try {
            val x509 = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
            val accepted = if (x509.basicConstraints >= 0) {
                isCaForServerAuth(x509) && isTrusted(arrayOf(x509))
            } else {
                allowsServerAuth(x509) && isTrustedLeaf(x509)
            }
            Log.i(IceDiagnostics.TAG, "TURNS certificate " + x509.subjectX500Principal.name + " accepted=" + accepted)
            accepted
        } catch (e: Exception) {
            Log.i(IceDiagnostics.TAG, "TURNS certificate is not trusted: " + e.javaClass.simpleName)
            false
        }
    }

    // Conscrypt needs a non-empty authType; it plays no role for the trust anchor lookup.
    @Suppress("SwallowedException")
    private fun isTrusted(chain: Array<X509Certificate>): Boolean =
        try {
            val manager = trustManager ?: return false
            manager.checkServerTrusted(chain, AUTH_TYPE)
            true
        } catch (e: CertificateException) {
            false
        }

    @VisibleForTesting
    internal fun isTrustedLeaf(leaf: X509Certificate): Boolean {
        // The leaf alone: the server certificate may be issued by an anchor directly.
        return isTrusted(arrayOf(leaf)) || extendChain(listOf(leaf))
    }

    /** Depth-first search; a chain is checked after each added certificate, as the anchor may be any of them. */
    private fun extendChain(chain: List<X509Certificate>): Boolean {
        val last = chain.last()
        if (chain.size > MAX_CHAIN_LENGTH || last.issuerX500Principal == last.subjectX500Principal) {
            return false
        }
        return intermediates
            .filter { it.subjectX500Principal == last.issuerX500Principal && chain.none { c -> c == it } }
            .filter { isIssuerOf(it, last) }
            .any { issuer ->
                val next = chain + issuer
                isTrusted(next.toTypedArray()) || extendChain(next)
            }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isIssuerOf(issuer: X509Certificate, cert: X509Certificate): Boolean =
        try {
            cert.verify(issuer.publicKey)
            issuer.basicConstraints >= 0
        } catch (e: Exception) {
            false
        }

    private fun allowsServerAuth(cert: X509Certificate): Boolean {
        val eku = cert.extendedKeyUsage
        return eku == null || SERVER_AUTH in eku || ANY_EKU in eku
    }

    private fun isCaForServerAuth(cert: X509Certificate): Boolean {
        val keyCertSign = cert.keyUsage?.getOrNull(KEY_CERT_SIGN_BIT) ?: true
        return cert.basicConstraints >= 0 && keyCertSign && allowsServerAuth(cert)
    }

    companion object {
        private val TAG = SystemTrustSslCertificateVerifier::class.java.simpleName
        private const val AUTH_TYPE = "UNKNOWN"
        private const val KEY_CERT_SIGN_BIT = 5
        private const val MAX_CHAIN_LENGTH = 6
        private const val BUNDLE = "turns-tls/lets-encrypt-intermediates.pem"
        private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
        private const val ANY_EKU = "2.5.29.37.0"

        private val instance: SystemTrustSslCertificateVerifier by lazy {
            SystemTrustSslCertificateVerifier(defaultTrustManager())
        }

        @Suppress("TooGenericExceptionCaught")
        private fun bundledIntermediates(): List<X509Certificate> =
            try {
                SystemTrustSslCertificateVerifier::class.java.classLoader!!.getResourceAsStream(BUNDLE)!!.use {
                    CertificateFactory.getInstance("X.509").generateCertificates(it).filterIsInstance<X509Certificate>()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load the bundled intermediates", e)
                emptyList()
            }

        @JvmStatic
        fun shared(): SystemTrustSslCertificateVerifier = instance

        @Suppress("TooGenericExceptionCaught")
        private fun defaultTrustManager(): X509TrustManager? =
            try {
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                factory.init(null as KeyStore?)
                factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load the system trust manager", e)
                null
            }
    }
}
