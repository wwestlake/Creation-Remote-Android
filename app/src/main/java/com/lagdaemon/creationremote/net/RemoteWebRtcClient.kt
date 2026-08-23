package com.lagdaemon.creationremote.net

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

private const val SIGNALING_URL = "wss://lagdaemon.com/djehuti/ws/remote/signaling"

/**
 * Connects to djehuti's /ws/remote/signaling relay as the "phone" role,
 * negotiates a direct P2P connection to the paired receiver, and creates
 * the DataChannel (the phone side is the one that opens it, per
 * docs/architecture/Creation-Remote-Protocol.md in the Creation-Suite repo
 * -- matches the receiver's WebRTCClient.cpp, which just catches it via
 * onDataChannel). No STUN-only-fails-fall-back-to-relay path here on
 * purpose: if a direct connection can't be negotiated, that's the same
 * "unreachable" outcome as everything else in this app's transport rules,
 * left to the caller to queue locally.
 */
class RemoteWebRtcClient(context: Context) {
    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private val eglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun connect(hostSessionId: String, grantToken: String) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val body = JSONObject().apply {
                    put("type", "ice")
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                }
                webSocket?.send(body.toString())
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onConnected?.invoke()
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> onDisconnected?.invoke()
                    else -> {}
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        })

        dataChannel = peerConnection?.createDataChannel("creation-remote", DataChannel.Init())

        val url = Uri.parse(SIGNALING_URL).buildUpon()
            .appendQueryParameter("hostSessionId", hostSessionId)
            .appendQueryParameter("role", "phone")
            .appendQueryParameter("token", grantToken)
            .build()
            .toString()

        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                createAndSendOffer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Signaling is text-only JSON; binary frames aren't expected here.
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError?.invoke(t.message ?: "Signaling connection failed")
            }
        })
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peerConnection?.setLocalDescription(NoopSdpObserver, description)
                val body = JSONObject().apply {
                    put("type", "offer")
                    put("sdp", description.description)
                }
                webSocket?.send(body.toString())
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { onError?.invoke(error) }
            override fun onSetFailure(error: String) { onError?.invoke(error) }
        }, constraints)
    }

    private fun handleSignalingMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "answer" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.optString("sdp"))
                peerConnection?.setRemoteDescription(NoopSdpObserver, sdp)
            }
            "ice" -> {
                val candidate = IceCandidate(json.optString("sdpMid"), 0, json.optString("candidate"))
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "done")
        webSocket = null
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
    }
}

private object NoopSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
