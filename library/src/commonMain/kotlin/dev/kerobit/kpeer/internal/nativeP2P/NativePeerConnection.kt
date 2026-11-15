package dev.kerobit.kpeer.internal.nativeP2P

import dev.kerobit.kpeer.KPeerConnectionState
import dev.kerobit.kpeer.KPeerContext
import dev.kerobit.kpeer.KPeerIceCandidate
import dev.kerobit.kpeer.KPeerSdpType
import dev.kerobit.kpeer.KPeerStatsReport
import dev.kerobit.kpeer.KChannelConfig
import dev.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow

internal expect class NativePeerConnection(
    config: TransportConfig,
    context: KPeerContext
) {
    val localIceCandidates: Flow<KPeerIceCandidate>
    val connectionState: Flow<KPeerConnectionState>
    val currentConnectionState: KPeerConnectionState
    val incomingDataChannels: Flow<NativeDataChannel>
    val negotiationNeeded: Flow<Unit>

    fun startPeerConnection()
    suspend fun createOffer(): String
    suspend fun createAnswer(): String
    suspend fun setRemoteDescription(type: KPeerSdpType, sdp: String)
    fun addIceCandidate(candidate: KPeerIceCandidate)
    suspend fun getStats(): KPeerStatsReport
    fun close()
    fun createDataChannel(config: KChannelConfig): NativeDataChannel?
}
