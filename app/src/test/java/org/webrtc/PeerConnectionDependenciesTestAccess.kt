/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.webrtc

// Lives in org.webrtc because the getters of PeerConnectionDependencies are package-private.
fun PeerConnectionDependencies.observer(): PeerConnection.Observer = getObserver()

fun PeerConnectionDependencies.sslCertificateVerifier(): SSLCertificateVerifier? = getSSLCertificateVerifier()
