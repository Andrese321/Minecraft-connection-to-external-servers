package com.bedrocklanproxy.core

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import java.nio.ByteBuffer

/** WebRTC de escritorio (webrtc-java, que envuelve libwebrtc) para probar NetherNet en el ordenador. */
class JvmRtcBackend : RtcBackend {
    private val factory by lazy { PeerConnectionFactory(HeadlessAudioDeviceModule()) }

    override fun createPeer(listener: RtcPeer.Listener): RtcPeer = Peer(listener)

    private inner class Peer(private val listener: RtcPeer.Listener) : RtcPeer {
        @Volatile private var reliable: RTCDataChannel? = null
        @Volatile private var closed = false

        private val pc: RTCPeerConnection = factory.createPeerConnection(
            RTCConfiguration(),
            object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) = listener.onLocalCandidate(candidate.sdp)

                override fun onDataChannel(channel: RTCDataChannel) {
                    if (channel.label != "ReliableDataChannel") return
                    reliable = channel
                    channel.registerObserver(object : RTCDataChannelObserver {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {
                            when (channel.state) {
                                RTCDataChannelState.OPEN -> listener.onReliableOpen()
                                RTCDataChannelState.CLOSED -> close("canal cerrado")
                                else -> Unit
                            }
                        }
                        override fun onMessage(buffer: RTCDataChannelBuffer) {
                            val b = ByteArray(buffer.data.remaining())
                            buffer.data.get(b)
                            listener.onReliableMessage(b)
                        }
                    })
                    if (channel.state == RTCDataChannelState.OPEN) listener.onReliableOpen()
                }

                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) close(state.name)
                }
            },
        )

        override fun answer(offerSdp: String, callback: (String?, String?) -> Unit) {
            pc.setRemoteDescription(
                RTCSessionDescription(RTCSdpType.OFFER, offerSdp),
                object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                            override fun onSuccess(answer: RTCSessionDescription) {
                                pc.setLocalDescription(answer, object : SetSessionDescriptionObserver {
                                    override fun onSuccess() = callback(pc.localDescription?.sdp ?: answer.sdp, null)
                                    override fun onFailure(error: String) = callback(null, error)
                                })
                            }
                            override fun onFailure(error: String) = callback(null, error)
                        })
                    }
                    override fun onFailure(error: String) = callback(null, error)
                },
            )
        }

        override fun addRemoteCandidate(candidate: String, mid: String) {
            pc.addIceCandidate(RTCIceCandidate(mid, 0, candidate))
        }

        override fun send(data: ByteArray) {
            val ch = reliable ?: return
            val buf = ByteBuffer.allocateDirect(data.size).put(data).also { it.flip() }
            ch.send(RTCDataChannelBuffer(buf, true))
        }

        override fun close() = close("cerrada")

        private fun close(reason: String) {
            if (closed) return
            closed = true
            Thread { try { pc.close() } catch (_: Throwable) {} }.start()
            listener.onClosed(reason)
        }
    }
}
