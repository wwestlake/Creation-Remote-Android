package com.lagdaemon.creationremote.webcam

import android.content.Context
import android.util.Log
import java.net.ConnectException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.RejectedExecutionException
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One capture pipeline and one authorized generation. All ownership changes are serialized. */
class WebCamSession(private val context: Context, private val preview: SurfaceViewRenderer,
                    private val invitation: PairingInvitation, private val rear: Boolean,
                    private val microphone: Boolean, private val report: (String) -> Unit,
                    private val onEnded: () -> Unit) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val ended = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var egl: EglBase? = null
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var video: VideoTrack? = null
    private var audio: AudioTrack? = null
    private var channel: DataChannel? = null
    private var sentOffer = false
    private val attempts = PairingAttempts(invitation.endpoints)
    private var attemptTimeout: ScheduledFuture<*>? = null
    private var answerAccepted = false
    @Volatile private var lastCameraTimestampNs = 0L
    private fun state(text: String) { main.post { report(text) } }
    private fun diagnostic(stage: String, error: Throwable? = null) {
        // Never log invitations, secrets, encrypted envelopes or SDP.
        Log.w("DjehutiWebCam", "$stage${error?.let { ": ${it.javaClass.simpleName}" } ?: ""}")
    }
    private fun submit(action: () -> Unit) {
        if(ended.get())return
        try {
            worker.execute { if(!ended.get()) try { action() } catch(error: Exception) {
                diagnostic("Session operation failed",error)
                state("Failed: Camera or media setup could not complete. Stop and try a fresh QR code.");close()
            } }
        } catch(_: RejectedExecutionException) { /* Late callback after disposal. */ }
    }
    fun start() = submit {
        state("Preparing camera and microphone…")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        egl = EglBase.create()
        main.post { if(!ended.get()) preview.init(egl!!.eglBaseContext, null) }
        adm = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext)).createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onIceConnectionChange(value: PeerConnection.IceConnectionState) {
                if(value == PeerConnection.IceConnectionState.CHECKING) state("Connecting: checking the direct local media path…")
            }
            override fun onIceConnectionReceivingChange(value: Boolean) {}
            override fun onSignalingChange(value: PeerConnection.SignalingState) {}
            override fun onAddStream(value: MediaStream) {}
            override fun onRemoveStream(value: MediaStream) {}
            override fun onDataChannel(value: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceGatheringChange(value: PeerConnection.IceGatheringState) {
                if(value == PeerConnection.IceGatheringState.COMPLETE) submit { sendOffer() }
            }
            override fun onConnectionChange(value: PeerConnection.PeerConnectionState) {
                when(value) {
                    PeerConnection.PeerConnectionState.CONNECTED -> if(!ended.get())state("Connected — transmitting over local WebRTC")
                    PeerConnection.PeerConnectionState.DISCONNECTED -> { state("Failed: Streaming was interrupted. Check local network access and scan a fresh QR code."); close() }
                    PeerConnection.PeerConnectionState.FAILED -> { state("Failed: Movie was reached, but the media connection failed. Check firewall rules and local network access, then scan a fresh QR code."); close() }
                    else -> {}
                }
            }
        }) ?: error("WebRTC peer unavailable")
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { if(rear) enumerator.isBackFacing(it) else enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: error("No camera available")
        capturer = enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(error: String) {state("Failed: Camera unavailable. Close other camera apps and try again.");close()}
            override fun onCameraDisconnected() {state("Failed: Camera disconnected. Try again.");close()}
            override fun onCameraFreezed(error: String) {state("Failed: Camera stopped delivering frames. Try again.");close()}
            override fun onCameraOpening(name: String) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        }) ?: error("Camera acquisition unavailable")
        helper = SurfaceTextureHelper.create("WebCamCapture", egl!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(false)
        val observer = videoSource!!.capturerObserver
        capturer!!.initialize(helper, context, object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = observer.onCapturerStarted(success)
            override fun onCapturerStopped() = observer.onCapturerStopped()
            override fun onFrameCaptured(frame: VideoFrame) { lastCameraTimestampNs = frame.timestampNs; observer.onFrameCaptured(frame) }
        })
        video = factory!!.createVideoTrack("webcam-video", videoSource)
        video!!.addSink(preview)
        peer!!.addTrack(video, listOf("webcam"))
        if(microphone) {
            audioSource = factory!!.createAudioSource(MediaConstraints())
            audio = factory!!.createAudioTrack("webcam-audio", audioSource)
            peer!!.addTrack(audio, listOf("webcam"))
        }
        for(transceiver in peer!!.transceivers) {
            val type=transceiver.mediaType
            val required=if(type==MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)"VP8" else "opus"
            val codecs=factory!!.getRtpSenderCapabilities(type).codecs.filter{it.name.equals(required,true)}
            check(codecs.isNotEmpty()){"Required codec unavailable"}
            transceiver.setCodecPreferences(codecs)
        }
        channel = peer!!.createDataChannel("webcam-timing-v1", DataChannel.Init())
        channel!!.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val received = System.nanoTime()
                if(buffer.binary || buffer.data.remaining()>4096)return
                val bytes = ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                submit {
                    val request=JSONObject(String(bytes,Charsets.UTF_8))
                    if(request.optString("type")=="clock-probe") {
                        val reply=JSONObject().put("type","clock-reply").put("generation",invitation.session)
                            .put("id",request.getString("id")).put("hostSend100ns",request.getString("hostSend100ns"))
                            .put("receiveNs",received.toString()).put("wallMs",System.currentTimeMillis().toString())
                            .put("cameraTimestampNs",lastCameraTimestampNs.toString()).put("sendNs",System.nanoTime().toString())
                        channel?.send(DataChannel.Buffer(ByteBuffer.wrap(reply.toString().toByteArray(Charsets.UTF_8)),false))
                    }
                }
            }
        })
        capturer!!.startCapture(1280,720,30)
        state("Connecting: preparing a secure local connection…")
        peer!!.createOffer(object:SimpleSdpObserver(){
            override fun onCreateSuccess(value: SessionDescription) = submit {
                peer!!.setLocalDescription(object:SimpleSdpObserver(){
                    override fun onSetSuccess() = submit { if(peer?.iceGatheringState()==PeerConnection.IceGatheringState.COMPLETE)sendOffer() }
                    override fun onSetFailure(error: String) {diagnostic("Set local description failed");state("Failed: Cannot prepare media negotiation. Try a fresh QR code.");close()}
                },value)
            }
            override fun onCreateFailure(error: String) {diagnostic("Create offer failed");state("Failed: Cannot prepare camera/audio streaming.");close()}
        },MediaConstraints())
        worker.schedule({ if(!ended.get() && peer?.connectionState()!=PeerConnection.PeerConnectionState.CONNECTED){
            state(if(answerAccepted) "Failed: Movie authenticated, but streaming did not start. Check the PC firewall and local network access."
                else "Failed: Connection timed out. Keep Pair Phone open and check local network access, then scan a fresh QR code.");close()
        } },90,TimeUnit.SECONDS)
    }
    private fun sendOffer() {
        if(sentOffer)return
        val sdp = peer?.localDescription?.description ?: return
        require(sdp.length<96000)
        sentOffer=true
        tryNextEndpoint()
    }
    private fun tryNextEndpoint() {
        attemptTimeout?.cancel(false)
        val previous=socket;socket=null;previous?.cancel()
        val attempt=attempts.next()
        if(attempt==null){state(attempts.failureMessage());close();return}
        state("Connecting: finding Movie on your local network…")
        diagnostic("Trying invitation endpoint ${attempt.id} of ${invitation.endpoints.size}")
        var opened = false
        fun failed(kind: PairingAttempts.Failure) {
            if(!answerAccepted && attempts.failed(attempt,kind)) tryNextEndpoint()
        }
        socket=http.newWebSocket(Request.Builder().url(attempt.endpoint).header("Origin","https://lagdaemon.com").build(),object:WebSocketListener(){
            override fun onOpen(webSocket: WebSocket,response: Response) = submit {
                if(!attempts.isCurrent(attempt)){webSocket.cancel();return@submit}
                opened=true
                state("Connecting: authenticating Movie…")
                val sdp=peer?.localDescription?.description ?: error("No local offer")
                if(!webSocket.send(invitation.encryptOffer(sdp)))failed(PairingAttempts.Failure.UNREACHABLE)
            }
            override fun onMessage(webSocket: WebSocket,text: String) = submit {
                if(!attempts.isCurrent(attempt) || answerAccepted)return@submit
                val answer=try { invitation.decryptAnswer(text) }
                catch(error: PairingRejectedException) {
                    diagnostic("Authenticated invitation rejection: ${error.code.takeIf { it in listOf("expired","consumed") } ?: "unknown"}")
                    state("Failed: This pairing invitation expired or was already used. Choose New QR code in Movie and scan again.");close();return@submit
                }
                catch(error: Exception) {
                    diagnostic("Answer authentication failed",error)
                    failed(PairingAttempts.Failure.AUTHENTICATION);return@submit
                }
                answerAccepted=true;attemptTimeout?.cancel(false)
                state("Connecting: starting camera and microphone stream…")
                peer!!.setRemoteDescription(object:SimpleSdpObserver(){
                    override fun onSetFailure(error: String) {diagnostic("Set remote description failed");state("Failed: Movie authenticated, but media negotiation failed. Try a fresh QR code.");close()}
                },SessionDescription(SessionDescription.Type.ANSWER,answer))
            }
            override fun onFailure(webSocket: WebSocket,error: Throwable,response: Response?) = submit {
                if(!attempts.isCurrent(attempt))return@submit
                diagnostic("Signaling connection failed",error)
                if(answerAccepted){state("Failed: Connection to Movie was interrupted. Check local network access and scan a fresh QR code.");close()}
                else failed(if(opened)PairingAttempts.Failure.AUTHENTICATION else if(error is ConnectException)PairingAttempts.Failure.REFUSED else PairingAttempts.Failure.UNREACHABLE)
            }
            override fun onClosed(webSocket: WebSocket,code: Int,reason: String) = submit {
                if(!attempts.isCurrent(attempt))return@submit
                if(answerAccepted){state("Failed: Movie disconnected. Scan a fresh QR code to reconnect.");close()}
                else failed(PairingAttempts.Failure.AUTHENTICATION)
            }
        })
        attemptTimeout=worker.schedule({if(!ended.get())failed(if(opened)PairingAttempts.Failure.AUTHENTICATION else PairingAttempts.Failure.UNREACHABLE)},5,TimeUnit.SECONDS)
    }
    fun switchCamera() = submit {capturer?.switchCamera(null)}
    fun setMicrophone(enabled: Boolean) = submit {
        if(audio==null && enabled)state("Microphone was not opened. Stop and reconnect with microphone enabled.")
        else audio?.setEnabled(enabled)
    }
    fun close() {
        if(!ended.compareAndSet(false,true))return
        worker.execute {
            attemptTimeout?.cancel(false)
            fun release(action:()->Unit){try{action()}catch(_:Exception){}}
            release{capturer?.stopCapture()};release{video?.removeSink(preview)};release{capturer?.dispose()};capturer=null
            release{socket?.close(1000,"stop")};release{socket?.cancel()};socket=null
            release{channel?.dispose()};channel=null;release{peer?.dispose()};peer=null
            release{video?.dispose()};release{audio?.dispose()};release{videoSource?.dispose()};release{audioSource?.dispose()}
            release{helper?.dispose()};release{factory?.dispose()};release{adm?.release()}
            main.post {release{preview.release()};release{egl?.release()};egl=null;onEnded()}
            http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()
            worker.shutdownNow()
        }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(value: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
