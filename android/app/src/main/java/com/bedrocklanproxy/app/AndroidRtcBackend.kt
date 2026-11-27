package com.bedrocklanproxy.app

import android.content.Context
import com.bedrocklanproxy.core.RtcBackend
import com.bedrocklanproxy.core.RtcPeer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/** WebRTC de Android (libwebrtc) para recibir a las consolas que entran por NetherNet (26.60+). */
class AndroidRtcBackend(context: Context) : RtcBackend {
    private val appContext = context.applicationContext

    private val factory: PeerConnectionFactory by lazy {
        synchronized(AndroidRtcBackend::class.java) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
                )
                initialized = true
            }
        }
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override fun createPeer(listener: RtcPeer.Listener): RtcPeer = Peer(listener)

    private inner class Peer(private val listener: RtcPeer.Listener) : RtcPeer {
        @Volatile private var reliable: DataChannel? = null
        @Volatile private var closed = false

        private val pc: PeerConnection = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()),
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) = listener.onLocalCandidate(candidate.sdp)

                override fun onDataChannel(channel: DataChannel) {
                    if (channel.label() != "ReliableDataChannel") return
                    reliable = channel
                    channel.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {
                            when (channel.state()) {
                                DataChannel.State.OPEN -> listener.onReliableOpen()
                                DataChannel.State.CLOSED -> close("canal cerrado")
                                else -> Unit
                            }
                        }
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            val b = ByteArray(buffer.data.remaining())
                            buffer.data.get(b)
                            listener.onReliableMessage(b)
                        }
                    })
                    if (channel.state() == DataChannel.State.OPEN) listener.onReliableOpen()
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    if (state == PeerConnection.PeerConnectionState.FAILED ||
                        state == PeerConnection.PeerConnectionState.CLOSED
                    ) close(state.name)
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            },
        ) ?: throw IllegalStateException("libwebrtc no pudo crear la conexión")

        override fun answer(offerSdp: String, callback: (String?, String?) -> Unit) {
            pc.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            pc.setLocalDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() = callback(pc.localDescription?.description ?: answer.description, null)
                                override fun onSetFailure(error: String) = callback(null, error)
                            }, answer)
                        }
                        override fun onCreateFailure(error: String) = callback(null, error)
                    }, MediaConstraints())
                }
                override fun onSetFailure(error: String) = callback(null, error)
            }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        }

        override fun addRemoteCandidate(candidate: String, mid: String) {
            pc.addIceCandidate(IceCandidate(mid, 0, candidate))
        }

        override fun send(data: ByteArray) {
            val ch = reliable ?: return
            val buf = ByteBuffer.allocateDirect(data.size).put(data)
            buf.flip()
            ch.send(DataChannel.Buffer(buf, true))
        }

        override fun close() = close("cerrada")

        private fun close(reason: String) {
            if (closed) return
            closed = true
            // Cerrar desde otro hilo: libwebrtc no permite cerrar desde sus propias llamadas.
            Thread { try { pc.close(); pc.dispose() } catch (_: Throwable) {} }.start()
            listener.onClosed(reason)
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }

    companion object {
        @Volatile private var initialized = false
    }
}
