package com.bedrocklanproxy.core

/** Firma una SDP de ejemplo (para verificar la firma con otra implementación). */
fun main() {
    val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n" +
        "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\n" +
        "a=fingerprint:sha-256 AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89\r\na=mid:0\r\n"
    print(NetherNetIdentity.generate().signAnswer(sdp))
}
