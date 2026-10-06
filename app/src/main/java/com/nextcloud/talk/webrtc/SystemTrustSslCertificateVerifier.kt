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
 * Lets WebRTC accept TURNS (TLS) server certificates that the platform trusts.
 *
 * The WebRTC build used by the app ships only a fixed list of root certificates (2023) which does not contain the
 * ISRG roots of Let's Encrypt, so the TLS handshake with a `turns:` server is aborted right after the Certificate
 * message. WebRTC calls this verifier only for a certificate that its built-in check has rejected; the native code
 * still checks the host name itself afterwards, so only the trust decision is made here.
 *
 * The certificate handed over is the one at the depth of the failure, which is not necessarily the leaf: for a chain
 * whose top certificate is cross-signed by a root WebRTC does not know, it is that top certificate. It is therefore
 * accepted only if the system trust store trusts it as a chain of one certificate (it is a trust anchor or is issued
 * by one). A certificate below an untrusted intermediate is rejected, and so is every error that the system check
 * does not clear.
 *
 * Called on a WebRTC network thread: no UI and no waiting. Certificates the user accepted for the server connection
 * in the app's own key store are not considered; that decision was made for a different purpose.
 */
class SystemTrustSslCertificateVerifier @JvmOverloads constructor(
    private val trustManager: X509TrustManager? = defaultTrustManager()
) : SSLCertificateVerifier {

    @Suppress("TooGenericExceptionCaught")
    override fun verify(certificate: ByteArray?): Boolean {
        if (certificate == null || trustManager == null) {
            return false
        }
        return try {
            val x509 = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
            // The authType must not be empty for Conscrypt; it plays no role for the trust anchor lookup.
            trustManager.checkServerTrusted(arrayOf(x509), AUTH_TYPE)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Certificate is not trusted by the system: " + e.javaClass.simpleName)
            false
        }
    }

    companion object {
        private val TAG = SystemTrustSslCertificateVerifier::class.java.simpleName
        private const val AUTH_TYPE = "UNKNOWN"

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
