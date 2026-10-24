/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.webrtc.TurnsTlsFixtures.pem
import com.nextcloud.talk.webrtc.TurnsTlsFixtures.verifierTrusting
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the Conscrypt trust manager, which Android uses. The PEM fixtures in `resources/turns-tls` have
 * realistic key usages (CA: keyCertSign, serverAuth + clientAuth); HeldCertificate sets none.
 */
class SystemTrustSslCertificateVerifierTest {

    private val heldRoot = HeldCertificate.Builder()
        .certificateAuthority(2)
        .commonName("Test Root")
        .build()

    private val verifier by lazy { verifierTrusting(pem("root")) }

    @Test
    fun trustAnchorItselfIsAccepted() {
        assertTrue(verifier.verify(pem("root")))
    }

    @Test
    fun intermediateCaIssuedByAnchorIsAccepted() {
        assertTrue(verifier.verify(pem("intermediate-ca")))
        assertTrue(verifier.verify(pem("intermediate-ca-no-eku")))
        assertTrue(verifier.verify(pem("intermediate-ca-any-eku")))
    }

    @Test
    fun intermediateCaWithoutKeyUsageIsAccepted() {
        val intermediate = HeldCertificate.Builder()
            .certificateAuthority(1)
            .commonName("Test Intermediate")
            .signedBy(heldRoot)
            .build()

        assertTrue(verifierTrusting(heldRoot.certificate.encoded).verify(intermediate.certificate.encoded))
    }

    @Test
    fun leafIssuedByAnchorIsAccepted() {
        val leaf = HeldCertificate.Builder()
            .commonName("turn.example.org")
            .addSubjectAlternativeName("turn.example.org")
            .signedBy(heldRoot)
            .build()

        assertTrue(verifierTrusting(heldRoot.certificate.encoded).verify(leaf.certificate.encoded))
        assertTrue(verifier.verify(pem("not-ca-under-root")))
    }

    @Test
    fun caWithoutKeyCertSignIsRejected() {
        assertFalse(verifier.verify(pem("ca-without-key-cert-sign")))
    }

    @Test
    fun caWithoutServerAuthIsRejected() {
        assertFalse(verifier.verify(pem("intermediate-ca-client-eku")))
    }

    @Test
    fun leafBelowUnknownIntermediateIsRejected() {
        val intermediate = HeldCertificate.Builder()
            .certificateAuthority(1)
            .commonName("Test Intermediate")
            .signedBy(heldRoot)
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName("turn.example.org")
            .addSubjectAlternativeName("turn.example.org")
            .signedBy(intermediate)
            .build()

        assertFalse(verifierTrusting(heldRoot.certificate.encoded).verify(leaf.certificate.encoded))
    }

    @Test
    fun foreignCertificatesAreRejected() {
        assertFalse(verifier.verify(pem("foreign-root")))
        val selfSigned = HeldCertificate.Builder().commonName("turn.example.org").build()
        assertFalse(verifier.verify(selfSigned.certificate.encoded))
    }

    @Test
    fun certificateWithAnchorNameButOtherKeyIsRejected() {
        val impostor = HeldCertificate.Builder()
            .certificateAuthority(2)
            .commonName("Test Root")
            .build()

        assertFalse(verifierTrusting(heldRoot.certificate.encoded).verify(impostor.certificate.encoded))
    }

    @Test
    fun expiredCaIsRejected() {
        assertFalse(verifier.verify(pem("expired-intermediate-ca")))
    }

    @Test
    fun garbageBytesAreRejected() {
        assertFalse(verifier.verify(byteArrayOf(1, 2, 3, 4)))
        assertFalse(verifier.verify(ByteArray(0)))
        assertFalse(verifier.verify(null))
    }

    @Test
    fun missingTrustManagerRejectsEverything() {
        assertFalse(SystemTrustSslCertificateVerifier(null).verify(pem("root")))
    }
}
