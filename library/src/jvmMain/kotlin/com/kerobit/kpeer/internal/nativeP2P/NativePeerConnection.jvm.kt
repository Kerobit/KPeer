package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal actual class NativePeerConnection actual constructor(
    config: TransportConfig,
    peerId: String,
    context: KPeerContext
) {
    actual val localIceCandidates: Flow<NativeIceCandidate> = emptyFlow()
    actual val connectionState: Flow<KPeerConnectionState> = emptyFlow()
    actual val currentConnectionState: KPeerConnectionState = KPeerConnectionState.DISCONNECTED
    actual val incomingDataChannels: Flow<NativeDataChannel> = emptyFlow()
    actual val negotiationNeeded: Flow<Unit> = emptyFlow()

    actual fun createDataChannel(label: String, ordered: Boolean, reliable: Boolean): NativeDataChannel? = null

    actual suspend fun createOffer(): NativeSdp {
        throw UnsupportedOperationException("P2P not supported on JVM")
    }

    actual suspend fun createAnswer(): NativeSdp {
        throw UnsupportedOperationException("P2P not supported on JVM")
    }

    actual suspend fun setRemoteDescription(sdp: NativeSdp) {
        throw UnsupportedOperationException("P2P not supported on JVM")
    }

    actual fun addIceCandidate(candidate: NativeIceCandidate) {
        throw UnsupportedOperationException("P2P not supported on JVM")
    }

    actual fun close() {}
}
