package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.KPeerIceCandidate
import com.kerobit.kpeer.KPeerSignal
import com.kerobit.kpeer.KPeerSdpType
import com.kerobit.kpeer.KPeerStatsReport
import com.kerobit.kpeer.KChannelConfig
import com.kerobit.kpeer.internal.TransportConfig
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

    suspend fun createOffer(): String
    suspend fun createAnswer(): String
    suspend fun setRemoteDescription(type: KPeerSdpType, sdp: String)
    fun addIceCandidate(candidate: KPeerIceCandidate)
    suspend fun getStats(): KPeerStatsReport
    fun close()
    fun createDataChannel(config: KChannelConfig): NativeDataChannel?
}
