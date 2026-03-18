package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.KPeerStatsReport
import com.kerobit.kpeer.ChannelConfig
import com.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow

internal expect class NativePeerConnection(
    config: TransportConfig,
    context: KPeerContext
) {
    val localIceCandidates: Flow<NativeIceCandidate>
    val connectionState: Flow<KPeerConnectionState>
    val currentConnectionState: KPeerConnectionState
    val incomingDataChannels: Flow<NativeDataChannel>
    val negotiationNeeded: Flow<Unit>

    suspend fun createOffer(): NativeSdp
    suspend fun createAnswer(): NativeSdp
    suspend fun setRemoteDescription(sdp: NativeSdp)
    fun addIceCandidate(candidate: NativeIceCandidate)
    suspend fun getStats(): KPeerStatsReport
    fun close()
    fun createDataChannel(config: ChannelConfig): NativeDataChannel?
}
