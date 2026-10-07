/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2017 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc;

import android.util.Log;

import com.bluelinelabs.logansquare.LoganSquare;
import com.nextcloud.talk.models.json.signaling.DataChannelMessageDto;
import com.nextcloud.talk.models.json.signaling.NCIceCandidateDto;
import com.nextcloud.talk.models.json.signaling.NCMessagePayloadDto;
import com.nextcloud.talk.models.json.signaling.NCSignalingMessageDto;
import com.nextcloud.talk.signaling.SignalingMessageReceiver;
import com.nextcloud.talk.signaling.SignalingMessageSender;

import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.IceCandidateErrorEvent;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionDependencies;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

public class PeerConnectionWrapper {

    private static final String TAG = PeerConnectionWrapper.class.getCanonicalName();

    private final SignalingMessageReceiver signalingMessageReceiver;
    private final WebRtcMessageListener webRtcMessageListener = new WebRtcMessageListener();

    private final SignalingMessageSender signalingMessageSender;

    private final DataChannelMessageNotifier dataChannelMessageNotifier = new DataChannelMessageNotifier();

    private final PeerConnectionNotifier peerConnectionNotifier = new PeerConnectionNotifier();

    private List<IceCandidate> iceCandidates = new ArrayList<>();
    private PeerConnection peerConnection;
    private String sessionId;
    private final MediaConstraints mediaConstraints;
    private final Map<String, DataChannel> dataChannels = new HashMap<>();
    private final List<DataChannelMessageDto> pendingDataChannelMessages = new ArrayList<>();
    private final SdpObserver sdpObserver;

    private final boolean isMCUPublisher;
    private final boolean hasMCU;
    private final String videoStreamType;

    // Identifies this connection towards the remote side, like "sid" in the web client and iOS. An offer with an
    // unknown "sid" makes the remote side drop its connection, so all messages of this connection carry it.
    private volatile String sid = String.valueOf(System.currentTimeMillis());

    @VisibleForTesting
    static volatile long disconnectedRestartDelayMs = 5000;
    @VisibleForTesting
    static volatile long unansweredOfferTimeoutMs = 10000;
    @VisibleForTesting
    static volatile long restartAfterRollbackJitterMs = 2000;
    private static final int MAX_AUTOMATIC_ICE_RESTARTS = 5;
    // All ICE restart work runs on this single thread, so the restart count needs no lock and PeerConnection
    // calls are never made while holding a lock the signaling thread needs.
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PeerConnectionWrapperTimer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile PeerConnection.IceConnectionState lastIceConnectionState;
    private int automaticIceRestarts; // only used on TIMER
    private ScheduledFuture<?> disconnectedRestartTask;
    private ScheduledFuture<?> unansweredOfferTask;

    // It is assumed that there will be at most one remote stream at each time.
    private MediaStream stream;
    private volatile boolean remoteAudioPlayoutEnabled = false;
    private final Map<String, AudioTrack> remoteAudioTracks = new HashMap<>();

    /**
     * Listener for data channel messages.
     * <p>
     * Messages might have been received on any data channel, independently of its label or whether it was open by the
     * local or the remote peer.
     * <p>
     * The messages are bound to a specific peer connection, so each listener is expected to handle messages only for
     * a single peer connection.
     * <p>
     * All methods are called on the so called "signaling" thread of WebRTC, which is an internal thread created by the
     * WebRTC library and NOT the same thread where signaling messages are received.
     */
    public interface DataChannelMessageListener {
        void onAudioOn();
        void onAudioOff();
        void onVideoOn();
        void onVideoOff();
        void onNickChanged(String nick);
    }

    /**
     * Observer for changes on the peer connection.
     * <p>
     * The changes are bound to a specific peer connection, so each observer is expected to handle messages only for
     * a single peer connection.
     * <p>
     * All methods are called on the so called "signaling" thread of WebRTC, which is an internal thread created by the
     * WebRTC library and NOT the same thread where signaling messages are received.
     */
    public interface PeerConnectionObserver {
        void onStreamAdded(MediaStream mediaStream);
        void onStreamRemoved(MediaStream mediaStream);
        void onIceConnectionStateChanged(PeerConnection.IceConnectionState iceConnectionState);
    }

    public PeerConnectionWrapper(PeerConnectionFactory peerConnectionFactory,
                                 List<PeerConnection.IceServer> iceServerList,
                                 MediaConstraints mediaConstraints,
                                 String sessionId, String localSession, @Nullable MediaStream localStream,
                                 boolean isMCUPublisher, boolean hasMCU, String videoStreamType,
                                 SignalingMessageReceiver signalingMessageReceiver,
                                 SignalingMessageSender signalingMessageSender) {
        this(peerConnectionFactory, iceServerList, mediaConstraints, sessionId, localSession, localStream,
             isMCUPublisher, hasMCU, videoStreamType, signalingMessageReceiver, signalingMessageSender, false);
    }

    /**
     * @param createdFromOffer true if the connection is created because an offer of the remote side arrived; no
     *                         offer is requested from the MCU then, as it is already sent
     */
    public PeerConnectionWrapper(PeerConnectionFactory peerConnectionFactory,
                                 List<PeerConnection.IceServer> iceServerList,
                                 MediaConstraints mediaConstraints,
                                 String sessionId, String localSession, @Nullable MediaStream localStream,
                                 boolean isMCUPublisher, boolean hasMCU, String videoStreamType,
                                 SignalingMessageReceiver signalingMessageReceiver,
                                 SignalingMessageSender signalingMessageSender,
                                 boolean createdFromOffer) {
        this.videoStreamType = videoStreamType;

        this.sessionId = sessionId;
        this.mediaConstraints = mediaConstraints;

        sdpObserver = new SdpObserver();
        boolean hasInitiated = isOfferer(localSession, sessionId);
        this.isMCUPublisher = isMCUPublisher;
        this.hasMCU = hasMCU;

        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServerList);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        // WebRTC knows no ISRG (Let's Encrypt) roots; let the trust anchors of the app decide for TURNS.
        PeerConnectionDependencies dependencies = PeerConnectionDependencies
            .builder(new InitialPeerConnectionObserver())
            .setSSLCertificateVerifier(SystemTrustSslCertificateVerifier.shared())
            .createPeerConnectionDependencies();
        peerConnection = peerConnectionFactory.createPeerConnection(configuration, dependencies);
        Log.i(IceDiagnostics.TAG, "Created " + videoStreamType + " connection over " + sessionId
            + " publisher=" + isMCUPublisher + " fromOffer=" + createdFromOffer + " ok=" + (peerConnection != null)
            + " iceServers=" + IceDiagnostics.describeServers(iceServerList));

        this.signalingMessageReceiver = signalingMessageReceiver;
        this.signalingMessageReceiver.addListener(webRtcMessageListener, sessionId, videoStreamType);

        this.signalingMessageSender = signalingMessageSender;

        if (peerConnection != null) {
            if (localStream != null) {
                List<String> localStreamIds = Collections.singletonList(localStream.getId());
                for(AudioTrack track : localStream.audioTracks) {
                    peerConnection.addTrack(track, localStreamIds);
                }
                for(VideoTrack track : localStream.videoTracks) {
                    peerConnection.addTrack(track, localStreamIds);
                }
            }

            if (hasMCU || hasInitiated) {
                DataChannel.Init init = new DataChannel.Init();
                init.negotiated = false;

                DataChannel statusDataChannel = peerConnection.createDataChannel("status", init);
                statusDataChannel.registerObserver(new DataChannelObserver(statusDataChannel));
                dataChannels.put("status", statusDataChannel);

                if (isMCUPublisher) {
                    peerConnection.createOffer(sdpObserver, mediaConstraints);
                } else if (hasMCU && "video".equals(this.videoStreamType) && !createdFromOffer) {
                    // If the connection is created because of an offer, a second request would make the MCU join
                    // again with a new "sid", which replaces this connection again and so on.
                    // If the connection type is "screen" the client sharing the screen will send an
                    // offer; offers should be requested only for videos.
                    // "to" property is not actually needed in the "requestoffer" signaling message, but it is used to
                    // set the recipient session ID in the assembled call message.
                    NCSignalingMessageDto ncSignalingMessage = createBaseSignalingMessage("requestoffer");
                    signalingMessageSender.send(ncSignalingMessage);
                } else if (!hasMCU && hasInitiated && "video".equals(this.videoStreamType)) {
                    // If the connection type is "screen" the client sharing the screen will send an
                    // offer; offers should be created only for videos.
                    peerConnection.createOffer(sdpObserver, mediaConstraints);
                }
            }
        }
    }

    /**
     * Decides whether this client takes the offerer role for a peer connection.
     *
     * <p>The role is resolved purely by lexicographic comparison of the two session IDs:
     * the client whose remote session ID sorts before its own local session ID becomes the
     * offerer. This yields exactly one offerer per pair <em>only if both peers compare the
     * same ordered pair of strings</em>.
     *
     * <p>Bug #6169: with the High-Performance Backend the remote {@code sessionId} is a
     * signaling-layer session ID. Callers must therefore pass a {@code localSession} from the
     * <em>same</em> namespace (the local signaling session, not the Nextcloud session); otherwise
     * the two peers compare unrelated strings and can both compute {@code false} (neither offers —
     * deadlock) or both {@code true} (offer collision). The {@code requestoffer} fallback only
     * applies in MCU mode, so a direct (no-MCU) 2-party call deadlocks outright. The deadlock is
     * exercised by {@code PeerConnectionWrapperOffererRoleTest}.
     *
     * @param localSession  this client's own session ID (same namespace as {@code remoteSession})
     * @param remoteSession the remote peer's session ID
     * @return true if this client should create the initial offer
     */
    static boolean isOfferer(String localSession, String remoteSession) {
        return remoteSession.compareTo(localSession) < 0;
    }

    /**
     * Restarts ICE with a new offer, e.g. after the device moved to another network. Skipped with the MCU (the call
     * is joined again there), for screen sharing, before the first negotiation is finished and while another
     * negotiation is in progress. Runs asynchronously.
     */
    public void restartIce() {
        runOnTimer(this::restartIceNow);
    }

    private static void runOnTimer(Runnable task) {
        TIMER.execute(() -> runSafely(task));
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "ICE restart task failed", e);
        }
    }

    @VisibleForTesting
    static void waitForTimerIdle() throws Exception {
        TIMER.submit(() -> { }).get();
    }

    private boolean restartIceNow() {
        PeerConnection connection = peerConnection;
        if (!canRestartIce(connection)) {
            return false;
        }

        Log.d(TAG, "Restarting ICE over " + sessionId + " " + videoStreamType);
        connection.restartIce();
        connection.createOffer(sdpObserver, mediaConstraints);
        scheduleUnansweredOfferRollback();
        return true;
    }

    private boolean canRestartIce(@Nullable PeerConnection connection) {
        return !hasMCU
            && "video".equals(videoStreamType)
            && connection != null
            && connection.signalingState() == PeerConnection.SignalingState.STABLE
            && connection.getLocalDescription() != null
            && connection.getRemoteDescription() != null;
    }

    private void restartIceWithinLimit() {
        if (automaticIceRestarts >= MAX_AUTOMATIC_ICE_RESTARTS) {
            Log.w(TAG, "Not restarting ICE again after " + MAX_AUTOMATIC_ICE_RESTARTS + " tries over " + sessionId);
        } else if (restartIceNow()) {
            automaticIceRestarts++;
        }
    }

    // An offer can get lost (e.g. dropped with the signaling connection); without an answer the connection would
    // stay in "have-local-offer" for good, so the offer is rolled back after a timeout.
    private void scheduleUnansweredOfferRollback() {
        synchronized (this) {
            cancelTask(unansweredOfferTask);
            unansweredOfferTask = TIMER.schedule(() -> runSafely(this::rollbackUnansweredOffer),
                                                 unansweredOfferTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private void rollbackUnansweredOffer() {
        PeerConnection connection = peerConnection;
        if (connection != null && connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.d(TAG, "No answer to the offer, rolling back over " + sessionId + " " + videoStreamType);
            connection.setLocalDescription(new RollbackObserver(this::restartIceIfStillBroken),
                                           new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
        }
    }

    // The jitter keeps two Android clients that dropped each other's offers from colliding again.
    private void restartIceIfStillBroken() {
        PeerConnection.IceConnectionState state = lastIceConnectionState;
        if (state == PeerConnection.IceConnectionState.DISCONNECTED
            || state == PeerConnection.IceConnectionState.FAILED) {
            long delay = (long) (Math.random() * restartAfterRollbackJitterMs);
            TIMER.schedule(() -> runSafely(this::restartIceWithinLimit), delay, TimeUnit.MILLISECONDS);
        }
    }

    // Same rule as the web client: ICE disconnected for more than 5 s, or failed, restarts ICE if the last local
    // description is an offer; at most 5 times in a row, counted again after a connection was established.
    private void handleIceStateForRestart(PeerConnection.IceConnectionState state) {
        if (hasMCU || !"video".equals(videoStreamType)) {
            return;
        }

        lastIceConnectionState = state;
        synchronized (this) {
            cancelTask(disconnectedRestartTask);
            disconnectedRestartTask = null;
            if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                disconnectedRestartTask = TIMER.schedule(() -> runSafely(this::restartIceIfStillDisconnected),
                                                         disconnectedRestartDelayMs, TimeUnit.MILLISECONDS);
            }
        }

        if (state == PeerConnection.IceConnectionState.CONNECTED
            || state == PeerConnection.IceConnectionState.COMPLETED) {
            runOnTimer(() -> automaticIceRestarts = 0);
        } else if (state == PeerConnection.IceConnectionState.FAILED) {
            runOnTimer(this::restartIceAutomatically);
        }
    }

    private void restartIceIfStillDisconnected() {
        if (lastIceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
            restartIceAutomatically();
        }
    }

    private void restartIceAutomatically() {
        PeerConnection connection = peerConnection;
        SessionDescription localDescription = connection == null ? null : connection.getLocalDescription();
        if (localDescription != null && localDescription.type == SessionDescription.Type.OFFER) {
            restartIceWithinLimit();
        }
    }

    private static void cancelTask(@Nullable ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    public void raiseHand(Boolean raise) {
        NCMessagePayloadDto ncMessagePayload = new NCMessagePayloadDto();
        ncMessagePayload.setState(raise);
        ncMessagePayload.setTimestamp(System.currentTimeMillis());

        NCSignalingMessageDto ncSignalingMessage = new NCSignalingMessageDto();
        ncSignalingMessage.setTo(sessionId);
        ncSignalingMessage.setType("raiseHand");
        ncSignalingMessage.setPayload(ncMessagePayload);
        ncSignalingMessage.setRoomType(videoStreamType);

        signalingMessageSender.send(ncSignalingMessage);
    }

    public void sendReaction(String emoji) {
        NCMessagePayloadDto ncMessagePayload = new NCMessagePayloadDto();
        ncMessagePayload.setReaction(emoji);
        ncMessagePayload.setTimestamp(System.currentTimeMillis());

        NCSignalingMessageDto ncSignalingMessage = new NCSignalingMessageDto();
        ncSignalingMessage.setTo(sessionId);
        ncSignalingMessage.setType("reaction");
        ncSignalingMessage.setPayload(ncMessagePayload);
        ncSignalingMessage.setRoomType(videoStreamType);

        signalingMessageSender.send(ncSignalingMessage);
    }

    /**
     * Adds a listener for data channel messages.
     * <p>
     * A listener is expected to be added only once. If the same listener is added again it will be notified just once.
     *
     * @param listener the DataChannelMessageListener
     */
    public void addListener(DataChannelMessageListener listener) {
        dataChannelMessageNotifier.addListener(listener);
    }

    public void removeListener(DataChannelMessageListener listener) {
        dataChannelMessageNotifier.removeListener(listener);
    }

    /**
     * Adds an observer for peer connection changes.
     * <p>
     * An observer is expected to be added only once. If the same observer is added again it will be notified just once.
     *
     * @param observer the PeerConnectionObserver
     */
    public void addObserver(PeerConnectionObserver observer) {
        peerConnectionNotifier.addObserver(observer);
    }

    public void removeObserver(PeerConnectionObserver observer) {
        peerConnectionNotifier.removeObserver(observer);
    }

    public String getVideoStreamType() {
        return videoStreamType;
    }

    public MediaStream getStream() {
        return stream;
    }

    public synchronized void setRemoteAudioPlayoutEnabled(boolean enabled) {
        remoteAudioPlayoutEnabled = enabled;
        double volume = enabled ? 1.0 : 0.0;
        Iterator<Map.Entry<String, AudioTrack>> iterator = remoteAudioTracks.entrySet().iterator();
        while (iterator.hasNext()) {
            try {
                iterator.next().getValue().setVolume(volume);
            } catch (IllegalStateException exception) {
                iterator.remove();
                Log.w(TAG, "Remote audio track was already disposed", exception);
            }
        }
    }

    private void applyRemoteAudioVolume(@Nullable MediaStream mediaStream) {
        if (mediaStream == null) {
            return;
        }
        double volume = remoteAudioPlayoutEnabled ? 1.0 : 0.0;
        for (AudioTrack audioTrack : mediaStream.audioTracks) {
            applyRemoteAudioVolume(audioTrack, volume);
        }
    }

    private void applyRemoteAudioVolume(AudioTrack audioTrack, double volume) {
        try {
            String trackId = audioTrack.id();
            audioTrack.setVolume(volume);
            Log.d(TAG, "Remote audio volume " + volume + " for track " + trackId + " over " + sessionId);
            remoteAudioTracks.put(trackId, audioTrack);
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Remote audio track was already disposed", exception);
        }
    }

    private void removeRemoteAudioTrack(MediaStreamTrack mediaStreamTrack) {
        try {
            String trackId = mediaStreamTrack.id();
            if (remoteAudioTracks.get(trackId) == mediaStreamTrack) {
                remoteAudioTracks.remove(trackId);
            }
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Remote audio track was already disposed", exception);
        }
    }

    public void removePeerConnection() {
        signalingMessageReceiver.removeListener(webRtcMessageListener);
        synchronized (this) {
            cancelTask(disconnectedRestartTask);
            cancelTask(unansweredOfferTask);
        }

        final PeerConnection connectionToClose;
        synchronized (this) {
            connectionToClose = peerConnection;
            peerConnection = null;
        }

        if (connectionToClose != null) {
            // close() blocks until the signaling thread finishes, and signaling thread callbacks
            // (onStateChange, onDataChannel) acquire this object's lock — so close() must be
            // called outside the lock. Nulling peerConnection above causes those callbacks to
            // return early once they acquire the lock.
            connectionToClose.close();
            Log.d(TAG, "Disposed PeerConnection");
        } else {
            Log.d(TAG, "PeerConnection is null.");
        }

        synchronized (this) {
            stream = null;
            remoteAudioTracks.clear();
            for (DataChannel dataChannel : dataChannels.values()) {
                String label;
                try {
                    label = dataChannel.label();
                } catch (IllegalStateException e) {
                    label = "<disposed>";
                }
                Log.d(TAG, "Disposed DataChannel " + label);

                dataChannel.unregisterObserver();
                dataChannel.dispose();
            }
            dataChannels.clear();
        }
    }

    private void drainIceCandidates() {

        if (peerConnection != null) {
            for (IceCandidate iceCandidate : iceCandidates) {
                peerConnection.addIceCandidate(iceCandidate);
            }

            iceCandidates = new ArrayList<>();
        }
    }

    private void addCandidate(IceCandidate iceCandidate) {
        if (peerConnection != null && peerConnection.getRemoteDescription() != null) {
            peerConnection.addIceCandidate(iceCandidate);
        } else {
            iceCandidates.add(iceCandidate);
        }
    }

    /**
     * Sends a data channel message.
     * <p>
     * Data channel messages are always sent on the "status" data channel locally opened. However, if Janus is used,
     * messages can be sent only on publisher connections, even if subscriber connections have a "status" data channel;
     * messages sent on subscriber connections will be simply ignored. Moreover, even if the message is sent on the
     * "status" data channel subscriber connections will receive it on a data channel with a different label, as
     * Janus opens its own data channel on subscriber connections and "multiplexes" all the received data channel
     * messages on it, independently of on which data channel they were originally sent.
     * <p>
     * Data channel messages can be sent at any time; if the "status" data channel is not open yet the messages will be
     * queued and sent once it is opened. Nevertheless, if Janus is used, it is not guaranteed that the messages will
     * be received by other participants, as it is only known when the data channel of the publisher was opened, but
     * not if the data channel of the subscribers was. However, in general this should be a concern only during the
     * first seconds after a participant joins; after some time the subscriber connections should be established and
     * their data channels open.
     *
     * @param dataChannelMessage the message to send
     */
    public synchronized void send(DataChannelMessageDto dataChannelMessage) {
        if (dataChannelMessage == null) {
            return;
        }

        DataChannel statusDataChannel = dataChannels.get("status");
        if (statusDataChannel == null || statusDataChannel.state() != DataChannel.State.OPEN ||
            !pendingDataChannelMessages.isEmpty()) {
            Log.d(TAG, "Queuing data channel message (" + dataChannelMessage + ") " + sessionId);

            pendingDataChannelMessages.add(dataChannelMessage);

            return;
        }

        sendWithoutQueuing(statusDataChannel, dataChannelMessage);
    }

    private void sendWithoutQueuing(DataChannel statusDataChannel, DataChannelMessageDto dataChannelMessage) {
        try {
            Log.d(TAG, "Sending data channel message (" + dataChannelMessage + ") " + sessionId);

            ByteBuffer buffer = ByteBuffer.wrap(LoganSquare.serialize(dataChannelMessage).getBytes());
            statusDataChannel.send(new DataChannel.Buffer(buffer, false));
        } catch (Exception e) {
            Log.w(TAG, "Failed to send data channel message");
        }
    }

    /**
     * Whether an offer with the given "sid" starts a new connection instead of renegotiating this one. The web
     * client and iOS drop the old connection in that case, so the owner should do the same.
     */
    public boolean isReplacedByOffer(@Nullable String offerSid) {
        PeerConnection connection = peerConnection;
        return offerSid != null && !offerSid.isEmpty() && !offerSid.equals(sid)
            && connection != null && connection.getRemoteDescription() != null;
    }

    public PeerConnection getPeerConnection() {
        return peerConnection;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isMCUPublisher() {
        return isMCUPublisher;
    }

    private boolean shouldNotReceiveVideo() {
        for (MediaConstraints.KeyValuePair keyValuePair : mediaConstraints.mandatory) {
            if ("OfferToReceiveVideo".equals(keyValuePair.getKey())) {
                return !Boolean.parseBoolean(keyValuePair.getValue());
            }
        }
        return false;
    }

    private NCSignalingMessageDto createBaseSignalingMessage(String type) {
        NCSignalingMessageDto ncSignalingMessage = new NCSignalingMessageDto();
        ncSignalingMessage.setTo(sessionId);
        ncSignalingMessage.setRoomType(videoStreamType);
        ncSignalingMessage.setType(type);
        // "requestoffer" with a "sid" asks to update an existing connection, so it must not carry our own one.
        if (!"requestoffer".equals(type)) {
            ncSignalingMessage.setSid(sid);
        }

        return ncSignalingMessage;
    }

    private class WebRtcMessageListener implements SignalingMessageReceiver.WebRtcMessageListener {

        public void onOffer(String sdp, String nick, String remoteSid) {
            PeerConnection connection = getPeerConnection();
            if (connection == null) {
                return;
            }

            if (hasSid(remoteSid) && !remoteSid.equals(sid) && connection.getRemoteDescription() != null) {
                // The owner of the connection replaces it when the "sid" changes; see isReplacedByOffer().
                Log.w(TAG, "Ignoring offer with sid " + remoteSid + " for connection " + sid + " " + sessionId);
                return;
            }

            // The state is read on the signaling thread, not on TIMER, so it can change right after. Crossed offers
            // and answers that may result are repaired by the answer check in onAnswer().
            if (connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                // Offer collision. The web client gives way (a browser rolls back its own offer by itself), so this
                // side keeps its offer: two sides that give way would answer crossed offers.
                Log.d(TAG, "Offer collision, keeping own offer over " + sessionId);
                return;
            }

            if (hasSid(remoteSid)) {
                sid = remoteSid;
            }
            connection.setRemoteDescription(sdpObserver, createSessionDescription("offer", sdp));
        }

        public void onAnswer(String sdp, String nick, String remoteSid) {
            if (isFromOtherConnection(remoteSid)) {
                return;
            }

            PeerConnection connection = getPeerConnection();
            if (connection == null) {
                return;
            }

            // Read on the signaling thread, see onOffer(); a wrong reading only costs one more ICE restart.
            if (connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                // The own offer was rolled back or lost a collision, so the sides disagree about ICE credentials.
                // A new offer brings them back in sync.
                Log.w(TAG, "Answer without own offer, restarting ICE over " + sessionId + " " + videoStreamType);
                runOnTimer(PeerConnectionWrapper.this::restartIceWithinLimit);
                return;
            }

            connection.setRemoteDescription(sdpObserver, createSessionDescription("answer", sdp));
        }

        private boolean hasSid(@Nullable String value) {
            return value != null && !value.isEmpty();
        }

        // Clients that do not send a "sid" (older ones) are treated as matching.
        private boolean isFromOtherConnection(String remoteSid) {
            if (hasSid(remoteSid) && !remoteSid.equals(sid)) {
                Log.d(TAG, "Ignoring message with sid " + remoteSid + " for connection " + sid + " " + sessionId);
                return true;
            }
            return false;
        }

        private SessionDescription createSessionDescription(String type, String sdp) {
            String sdpWithPreferredCodec = WebRTCUtils.preferCodec(sdp, "H264", false);
            return new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdpWithPreferredCodec);
        }

        public void onCandidate(String sdpMid, int sdpMLineIndex, String sdp, String remoteSid) {
            if (isFromOtherConnection(remoteSid)) {
                return;
            }
            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
            addCandidate(iceCandidate);
        }

        public void onEndOfCandidates() {
            drainIceCandidates();
        }
    }

    private class DataChannelObserver implements DataChannel.Observer {

        private final DataChannel dataChannel;
        private final String dataChannelLabel;

        public DataChannelObserver(DataChannel dataChannel) {
            this.dataChannel = Objects.requireNonNull(dataChannel);
            this.dataChannelLabel = dataChannel.label();
        }

        @Override
        public void onBufferedAmountChange(long l) {

        }

        @Override
        public void onStateChange() {
            synchronized (PeerConnectionWrapper.this) {
                // The PeerConnection could have been removed in parallel even with the synchronization (as just after
                // "onStateChange" was called "removePeerConnection" could have acquired the lock).
                if (peerConnection == null) {
                    return;
                }

                if (dataChannel.state() == DataChannel.State.OPEN && "status".equals(dataChannelLabel)) {
                    for (DataChannelMessageDto dataChannelMessage : pendingDataChannelMessages) {
                        sendWithoutQueuing(dataChannel, dataChannelMessage);
                    }
                    pendingDataChannelMessages.clear();
                }
            }
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            synchronized (PeerConnectionWrapper.this) {
                // It is assumed that, even if its data channel was disposed, its buffers can be used while there is
                // a reference to them, so it would not be necessary to check this from a thread-safety point of view.
                // Nevertheless, if the remote peer connection was removed it would not make sense to notify the
                // listeners anyway.
                if (peerConnection == null) {
                    return;
                }
            }

            if (buffer.binary) {
                Log.d(TAG, "Received binary data channel message over " + dataChannelLabel + " " + sessionId);
                return;
            }

            ByteBuffer data = buffer.data;
            final byte[] bytes = new byte[data.capacity()];
            data.get(bytes);
            String strData = new String(bytes);
            Log.d(TAG, "Received data channel message (" + strData + ") over " + dataChannelLabel + " " + sessionId);

            DataChannelMessageDto dataChannelMessage;
            try {
                dataChannelMessage = LoganSquare.parse(strData, DataChannelMessageDto.class);
            } catch (IOException e) {
                Log.d(TAG, "Failed to parse data channel message");

                return;
            }

            if ("nickChanged".equals(dataChannelMessage.getType())) {
                String nick = null;
                if (dataChannelMessage.getPayload() instanceof String) {
                    nick = (String) dataChannelMessage.getPayload();
                } else if (dataChannelMessage.getPayload() instanceof Map) {
                    Map<String, String> payloadMap = (Map<String, String>) dataChannelMessage.getPayload();
                    nick = payloadMap.get("name");
                }

                if (nick != null) {
                    dataChannelMessageNotifier.notifyNickChanged(nick);
                }

                return;
            }

            if ("audioOn".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyAudioOn();

                return;
            }

            if ("audioOff".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyAudioOff();

                return;
            }

            if ("videoOn".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyVideoOn();

                return;
            }

            if ("videoOff".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyVideoOff();

                return;
            }
        }
    }

    private class InitialPeerConnectionObserver implements PeerConnection.Observer {

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (peerConnection == null) {
                return;
            }

            Log.d("iceConnectionChangeTo: ", iceConnectionState.name() + " over " + peerConnection.hashCode() + " " + sessionId);

            peerConnectionNotifier.notifyIceConnectionStateChanged(iceConnectionState);
            handleIceStateForRestart(iceConnectionState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.i(IceDiagnostics.TAG, "Gathering " + iceGatheringState + " over " + sessionId + " "
                + videoStreamType + " sid=" + sid);
        }

        @Override
        public void onIceCandidateError(IceCandidateErrorEvent event) {
            Log.w(IceDiagnostics.TAG, "Candidate error over " + sessionId + " " + videoStreamType + " sid=" + sid
                + " url=" + event.url + " code=" + event.errorCode + " text=" + event.errorText);
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            if (IceDiagnostics.isLoopbackCandidate(iceCandidate.sdp)) {
                Log.i(IceDiagnostics.TAG, "Skipping loopback candidate over " + sessionId + " " + videoStreamType);
                return;
            }
            NCSignalingMessageDto ncSignalingMessage = createBaseSignalingMessage("candidate");
            Log.i(IceDiagnostics.TAG, "Sending candidate " + IceDiagnostics.describeCandidate(iceCandidate.sdp)
                + " server=" + iceCandidate.serverUrl + " over " + sessionId + " " + videoStreamType
                + " sid=" + ncSignalingMessage.getSid());
            NCMessagePayloadDto ncMessagePayload = new NCMessagePayloadDto();
            ncMessagePayload.setType("candidate");

            NCIceCandidateDto ncIceCandidate = new NCIceCandidateDto();
            ncIceCandidate.setSdpMid(iceCandidate.sdpMid);
            ncIceCandidate.setSdpMLineIndex(iceCandidate.sdpMLineIndex);
            ncIceCandidate.setCandidate(iceCandidate.sdp);
            ncMessagePayload.setIceCandidate(ncIceCandidate);

            ncSignalingMessage.setPayload(ncMessagePayload);

            signalingMessageSender.send(ncSignalingMessage);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "onAddStream audio=" + mediaStream.audioTracks.size() + " video=" + mediaStream.videoTracks.size()
                + " over " + sessionId + " " + videoStreamType);
            synchronized (PeerConnectionWrapper.this) {
                stream = mediaStream;
                applyRemoteAudioVolume(mediaStream);
            }

            peerConnectionNotifier.notifyStreamAdded(mediaStream);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            synchronized (PeerConnectionWrapper.this) {
                stream = null;
                for (AudioTrack audioTrack : mediaStream.audioTracks) {
                    removeRemoteAudioTrack(audioTrack);
                }
            }

            peerConnectionNotifier.notifyStreamRemoved(mediaStream);
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            synchronized (PeerConnectionWrapper.this) {
                // Another data channel with the same label, no matter if the same instance or a different one, should
                // not be added, but this is handled just in case.
                // Moreover, if it were possible that an already added data channel was added again there would be a
                // potential race condition with "removePeerConnection", even with the synchronization, as it would
                // be possible that "onDataChannel" was called, then "removePeerConnection" disposed the data
                // channel, and then "onDataChannel" continued in the synchronized statements and tried to get the
                // label, which would throw an exception due to the data channel having been disposed already.
                String dataChannelLabel;
                try {
                    dataChannelLabel = dataChannel.label();
                } catch (IllegalStateException e) {
                    // The data channel was disposed already, nothing to do.
                    return;
                }

                DataChannel oldDataChannel = dataChannels.get(dataChannelLabel);
                if (oldDataChannel == dataChannel) {
                    Log.w(TAG, "Data channel with label " + dataChannelLabel + " added again");

                    return;
                }

                if (oldDataChannel != null) {
                    Log.w(TAG, "Data channel with label " + dataChannelLabel + " exists");

                    // Remove the old entry first so that removePeerConnection() cannot iterate over
                    // a channel that we are about to dispose (it would throw when calling label() on it).
                    dataChannels.remove(dataChannelLabel);
                    oldDataChannel.unregisterObserver();
                    oldDataChannel.dispose();
                }

                // If the peer connection was removed in parallel dispose the data channel instead of adding it.
                if (peerConnection == null) {
                    dataChannel.dispose();

                    return;
                }

                dataChannel.registerObserver(new DataChannelObserver(dataChannel));
                dataChannels.put(dataChannelLabel, dataChannel);
            }
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            MediaStreamTrack track = rtpReceiver.track();
            Log.d(TAG, "onAddTrack " + (track == null ? null : track.kind()) + " streams=" + mediaStreams.length
                + " over " + sessionId + " " + videoStreamType);
            synchronized (PeerConnectionWrapper.this) {
                if (track instanceof AudioTrack) {
                    AudioTrack audioTrack = (AudioTrack) track;
                    applyRemoteAudioVolume(audioTrack, remoteAudioPlayoutEnabled ? 1.0 : 0.0);
                }
                for (MediaStream mediaStream : mediaStreams) {
                    stream = mediaStream;
                    applyRemoteAudioVolume(mediaStream);
                }
            }
        }

        @Override
        public void onRemoveTrack(RtpReceiver rtpReceiver) {
            MediaStreamTrack track = rtpReceiver.track();
            if (track instanceof AudioTrack) {
                synchronized (PeerConnectionWrapper.this) {
                    removeRemoteAudioTrack(track);
                }
            }
        }
    }

    private void stopVideoTransceiversIfNotReceivingVideo() {
        if (shouldNotReceiveVideo()) {
            for (RtpTransceiver t : peerConnection.getTransceivers()) {
                if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !t.isStopped()) {
                    t.stop();
                }
            }
            Log.d(TAG, "Stop all Transceivers for MEDIA_TYPE_VIDEO.");
        }
    }

    /**
     * Observer for the rollback of an own offer; runs {@code afterRollback} once it was applied.
     */
    private class RollbackObserver implements org.webrtc.SdpObserver {
        @Nullable
        private final Runnable afterRollback;

        RollbackObserver(@Nullable Runnable afterRollback) {
            this.afterRollback = afterRollback;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
        }

        @Override
        public void onSetSuccess() {
            if (afterRollback != null) {
                afterRollback.run();
            }
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
            Log.w(TAG, "Rollback failed over " + sessionId + ": " + s);
        }
    }

    private class SdpObserver implements org.webrtc.SdpObserver {
        private static final String TAG = "SdpObserver";

        @Override
        public void onCreateFailure(String s) {
            Log.d(TAG, "SDPObserver createFailure: " + s + " over " + sessionId);

        }

        @Override
        public void onSetFailure(String s) {
            Log.d(TAG,"SDPObserver setFailure: " + s + " over " + sessionId);
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            String type = sessionDescription.type.canonicalForm();

            NCSignalingMessageDto ncSignalingMessage = createBaseSignalingMessage(type);
            NCMessagePayloadDto ncMessagePayload = new NCMessagePayloadDto();
            ncMessagePayload.setType(type);

            SessionDescription sessionDescriptionWithPreferredCodec;
            String sessionDescriptionStringWithPreferredCodec = WebRTCUtils.preferCodec
                    (sessionDescription.description,
                            "H264", false);
            sessionDescriptionWithPreferredCodec = new SessionDescription(
                    sessionDescription.type,
                    sessionDescriptionStringWithPreferredCodec);

            ncMessagePayload.setSdp(sessionDescriptionWithPreferredCodec.description);

            ncSignalingMessage.setPayload(ncMessagePayload);

            signalingMessageSender.send(ncSignalingMessage);

            if (peerConnection != null) {
                peerConnection.setLocalDescription(sdpObserver, sessionDescriptionWithPreferredCodec);
            }
        }

        @Override
        public void onSetSuccess() {
            if (peerConnection != null) {
                if (peerConnection.getLocalDescription() == null) {

                    stopVideoTransceiversIfNotReceivingVideo();

                    /*
                        Passed 'MediaConstraints' will be ignored by WebRTC when using UNIFIED PLAN.
                        See for details: https://docs.google.com/document/d/1PPHWV6108znP1tk_rkCnyagH9FK205hHeE9k5mhUzOg/edit#heading=h.9dcmkavg608r
                     */
                    peerConnection.createAnswer(sdpObserver, new MediaConstraints());

                } else if (peerConnection.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                    // The other side restarted ICE (or renegotiates) on an already negotiated connection.
                    stopVideoTransceiversIfNotReceivingVideo();
                    peerConnection.createAnswer(sdpObserver, new MediaConstraints());
                }

                if (peerConnection.getRemoteDescription() != null) {
                    drainIceCandidates();
                }
            }
        }
    }
}
