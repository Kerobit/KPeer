package dev.kerobit.kpeer.internal.nativeP2P

import dev.kerobit.kpeer.KChannelConfig
import dev.kerobit.kpeer.KPeerConnectionState
import dev.kerobit.kpeer.KPeerContext
import dev.kerobit.kpeer.KPeerIceCandidate
import dev.kerobit.kpeer.KPeerSdpType
import dev.kerobit.kpeer.KPeerStatsReport
import dev.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal actual class NativePeerConnection actual constructor(
    config: TransportConfig,
    context: KPeerContext
) {
    actual val localIceCandidates: Flow<KPeerIceCandidate> = emptyFlow()
    actual val connectionState: Flow<KPeerConnectionState> = emptyFlow()
    actual val currentConnectionState: KPeerConnectionState = KPeerConnectionState.DISCONNECTED
    actual val incomingDataChannels: Flow<NativeDataChannel> = emptyFlow()
    actual val negotiationNeeded: Flow<Unit> = emptyFlow()

    actual fun startPeerConnection() {}

    actual fun createDataChannel(config: KChannelConfig): NativeDataChannel? = null

    actual suspend fun createOffer(): String {
        throw UnsupportedOperationException("P2P not supported on MinGW")
    }

    actual suspend fun createAnswer(): String {
        throw UnsupportedOperationException("P2P not supported on MinGW")
    }

    actual suspend fun setRemoteDescription(type: KPeerSdpType, sdp: String) {
        throw UnsupportedOperationException("P2P not supported on MinGW")
    }

    actual fun addIceCandidate(candidate: KPeerIceCandidate) {
        throw UnsupportedOperationException("P2P not supported on MinGW")
    }

    actual suspend fun getStats(): KPeerStatsReport {
        throw UnsupportedOperationException("P2P not supported on MinGW")
    }

    actual fun close() {}
}
