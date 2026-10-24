/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.webrtc.TurnsTlsFixtures.cert
import com.nextcloud.talk.webrtc.TurnsTlsFixtures.pem
import com.nextcloud.talk.webrtc.TurnsTlsFixtures.verifierTrusting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/** Leaf certificates, as passed by libwebrtc 155 (`VerifyChain` hands over only the leaf). */
class SystemTrustSslCertificateVerifierLeafTest {

    // Hierarchy like Let's Encrypt: anchor Root A (as ISRG Root X1) <- Root B cross-signed by A (Root YE/X2)
    // <- intermediate B1 (YE1) <- leaf. The server sends the intermediates, WebRTC 155 passes only the leaf.
    private val leLikeIntermediates by lazy { listOf(cert("le-like-root-b-cross"), cert("le-like-intermediate")) }

    private val leLikeVerifier by lazy { verifierTrusting(pem("le-like-root"), leLikeIntermediates) }

    @Test
    fun leafWithBundledIntermediatesReachingTheAnchorIsAccepted() {
        assertTrue(leLikeVerifier.verify(pem("le-like-leaf")))
        assertTrue(leLikeVerifier.verify(pem("le-like-leaf-no-eku")))
    }

    @Test
    fun leafIsRejectedWithoutTheIntermediates() {
        assertFalse(verifierTrusting(pem("le-like-root"), emptyList()).verify(pem("le-like-leaf")))
        val withoutRootB = verifierTrusting(pem("le-like-root"), listOf(cert("le-like-intermediate")))
        assertFalse(withoutRootB.verify(pem("le-like-leaf")))
    }

    @Test
    fun leafIsRejectedWhenTheIntermediatesDoNotReachTheAnchor() {
        assertFalse(verifierTrusting(pem("foreign-root"), leLikeIntermediates).verify(pem("le-like-leaf")))
    }

    @Test
    fun anchorWithSameNameButOtherKeyIsRejected() {
        assertFalse(verifierTrusting(pem("le-like-foreign-root"), leLikeIntermediates).verify(pem("le-like-leaf")))
    }

    @Test
    fun leafOfAnotherRootWithSameNamesIsRejected() {
        assertFalse(leLikeVerifier.verify(pem("le-like-foreign-leaf")))
    }

    @Test
    fun expiredLeafIsRejected() {
        assertFalse(leLikeVerifier.verify(pem("le-like-leaf-expired")))
    }

    @Test
    fun selfSignedLeafIsRejected() {
        assertFalse(leLikeVerifier.verify(pem("le-like-leaf-self-signed")))
    }

    @Test
    fun leafForClientAuthOnlyIsRejected() {
        assertFalse(leLikeVerifier.verify(pem("le-like-leaf-client-eku")))
    }

    @Test
    fun bundledIntermediatesLoadAndReachIsrgRootX1() {
        val bundled = verifierTrusting(pem("isrg-root-x1"))
        val bundle = javaClass.classLoader!!.getResourceAsStream("turns-tls/lets-encrypt-intermediates.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).filterIsInstance<X509Certificate>()
        }
        assertTrue(bundle.size >= 18)
        assertTrue(bundle.any { it.subjectX500Principal.name.contains("CN=YE1") })
        assertTrue(bundled.verify(bundle.first { it.subjectX500Principal.name.contains("CN=R12") }.encoded))
    }

    private fun bundle(): List<X509Certificate> =
        javaClass.classLoader!!.getResourceAsStream("turns-tls/lets-encrypt-intermediates.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).filterIsInstance<X509Certificate>()
        }

    private fun ye1() = bundle().first { it.subjectX500Principal.name.contains("CN=YE1") }

    @Test
    fun bundledYe1ReachesIsrgRootX1ThroughTheCrossSignedX2() {
        // YE1 -> Root YE (by X2) -> ISRG Root X2 (by X1) -> X1: the chain of the stand, without its leaf.
        assertTrue(verifierTrusting(pem("isrg-root-x1")).isTrustedLeaf(ye1()))
    }

    @Test
    fun bundledYe1IsRejectedWithoutTheX2CrossSignedByX1() {
        val withoutX2ByX1 = bundle().filterNot {
            it.subjectX500Principal.name.contains("ISRG Root X2") &&
                it.issuerX500Principal.name.contains("ISRG Root X1")
        }
        assertTrue(withoutX2ByX1.size == bundle().size - 1)
        assertFalse(verifierTrusting(pem("isrg-root-x1"), withoutX2ByX1).isTrustedLeaf(ye1()))
    }

    /**
     * Real chain of the TURNS host: leaf -> YE1 -> Root YE (cross-signed by ISRG Root X2) -> ISRG Root X2
     * (cross-signed by ISRG Root X1) -> ISRG Root X1 as the only anchor (Android 12 has no Root X2). The leaf names
     * the test stand, so it is not committed: put it to `resources/turns-tls/cloud-crt-leaf.pem` to run this test.
     */
    @Test
    fun realStandLeafIsAcceptedWithAnchorIsrgRootX1() {
        val stream = javaClass.classLoader!!.getResourceAsStream("turns-tls/cloud-crt-leaf.pem")
        Assume.assumeTrue("no stand leaf in the test resources", stream != null)
        val leaf = stream!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
        Assume.assumeTrue("stand leaf expired", leaf.notAfter.after(Date()))

        assertTrue(verifierTrusting(pem("isrg-root-x1")).verify(leaf.encoded))
    }
}
