/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** PEM fixtures in `resources/turns-tls` and a verifier backed by the Conscrypt trust manager, as on Android. */
object TurnsTlsFixtures {

    fun pem(name: String): ByteArray {
        val stream = TurnsTlsFixtures::class.java.classLoader!!.getResourceAsStream("turns-tls/$name.pem")!!
        return CertificateFactory.getInstance("X.509").generateCertificate(stream).encoded
    }

    fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem(name).inputStream()) as X509Certificate

    fun verifierTrusting(
        anchor: ByteArray,
        intermediates: List<X509Certificate>? = null
    ): SystemTrustSslCertificateVerifier {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(anchor.inputStream()) as X509Certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("anchor", cert)
        }
        val factory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
            Conscrypt.newProvider()
        )
        factory.init(keyStore)
        val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        return if (intermediates == null) {
            SystemTrustSslCertificateVerifier(trustManager)
        } else {
            SystemTrustSslCertificateVerifier(trustManager) { intermediates }
        }
    }
}
