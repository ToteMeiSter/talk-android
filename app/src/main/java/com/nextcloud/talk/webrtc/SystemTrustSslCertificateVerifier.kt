/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import android.util.Log
import org.webrtc.SSLCertificateVerifier
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Accepts a CA certificate for TURNS which the built-in WebRTC roots (no ISRG/Let's Encrypt) rejected.
 *
 * WebRTC calls this only after its own check failed, with the certificate at the failing depth and without the error
 * type, so only a CA is accepted here: a non-CA, or a CA without keyCertSign or serverAuth, would clear errors of
 * another kind. It must also be trusted alone by the trust anchors of the app's network security config (system and
 * user CAs; a `domain-config` there makes the check fail). The host name is still checked by WebRTC.
 */
class SystemTrustSslCertificateVerifier(private val trustManager: X509TrustManager?) : SSLCertificateVerifier {

    @Suppress("TooGenericExceptionCaught")
    override fun verify(certificate: ByteArray?): Boolean {
        if (certificate == null || trustManager == null) {
            return false
        }
        return try {
            val x509 = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
            val isCa = isCaForServerAuth(x509)
            if (isCa) {
                // Conscrypt needs a non-empty authType; it plays no role for the trust anchor lookup.
                trustManager.checkServerTrusted(arrayOf(x509), AUTH_TYPE)
            }
            Log.i(IceDiagnostics.TAG, "TURNS certificate " + x509.subjectX500Principal.name + " accepted=" + isCa)
            isCa
        } catch (e: Exception) {
            Log.i(IceDiagnostics.TAG, "TURNS certificate is not trusted: " + e.javaClass.simpleName)
            false
        }
    }

    private fun isCaForServerAuth(cert: X509Certificate): Boolean {
        val keyCertSign = cert.keyUsage?.getOrNull(KEY_CERT_SIGN_BIT) ?: true
        val eku = cert.extendedKeyUsage
        return cert.basicConstraints >= 0 && keyCertSign && (eku == null || SERVER_AUTH in eku || ANY_EKU in eku)
    }

    companion object {
        private val TAG = SystemTrustSslCertificateVerifier::class.java.simpleName
        private const val AUTH_TYPE = "UNKNOWN"
        private const val KEY_CERT_SIGN_BIT = 5
        private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
        private const val ANY_EKU = "2.5.29.37.0"

        private val instance: SystemTrustSslCertificateVerifier by lazy {
            SystemTrustSslCertificateVerifier(defaultTrustManager())
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
