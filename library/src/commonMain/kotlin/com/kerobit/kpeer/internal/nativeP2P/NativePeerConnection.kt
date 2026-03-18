package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow

internal expect class NativePeerConnection(
    config: TransportConfig,
    peerId: String,
    context: KPeerContext
) {
    val localIceCandidates: Flow<NativeIceCandidate>
    val connectionState: Flow<KPeerConnectionState>
    val currentConnectionState: KPeerConnectionState
    val incomingDataChannels: Flow<NativeDataChannel>

    suspend fun createOffer(): NativeSdp
    suspend fun createAnswer(): NativeSdp
    suspend fun setRemoteDescription(sdp: NativeSdp)
    fun addIceCandidate(candidate: NativeIceCandidate)
    fun close()
    fun createDataChannel(label: String, ordered: Boolean, reliable: Boolean): NativeDataChannel?
}
