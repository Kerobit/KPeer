# KPeer

`KPeer` is a Kotlin Multiplatform WebRTC transport focused on peer-to-peer data channels.

It gives you:

- signaling primitives (`Offer`, `Answer`, `IceCandidate`)
- one `PeerConnection` per peer
- multiple `DataChannel`s on the same connection
- channel-level send/receive APIs
- renegotiation when new channels are created after the initial connection setup

## Core concepts

- `KPeer` owns the WebRTC peer connection and signaling flow
- `KChannel` owns sending and receiving data for one data channel
- `KPeerSignal` is the payload you exchange through your own signaling backend

Transport and signaling are intentionally separate. `KPeer` does not ship a signaling server.

## Creating a peer

```kotlin
val peer = KPeer(
    context = KPeerContext(
        platformContext = androidContext // Android only
    ),
    config = KPeerConfig(
        initiator = true
    )
)
```

If you already have an application scope, you can still pass it explicitly:

```kotlin
val peer = KPeer(
    context = KPeerContext(
        scope = scope,
        platformContext = androidContext
    ),
    config = KPeerConfig(initiator = true)
)
```

`initiator = true` starts as the side that creates the first offer.  
`initiator = false` waits until it receives an `Offer`.

## Signaling

You must forward every emitted `KPeerSignal` to the remote peer with your own signaling layer:

```kotlin
scope.launch {
    peer.signals.collect { signal ->
        signalingBackend.send(signal)
    }
}
```

And when a remote signaling message arrives:

```kotlin
suspend fun onRemoteSignal(signal: KPeerSignal) {
    peer.signal(signal)
}
```

Supported signaling messages:

- `KPeerSignal.Offer`
- `KPeerSignal.Answer`
- `KPeerSignal.IceCandidate`

## Creating channels

Create a channel with its own configuration:

```kotlin
val chatChannel = peer.createChannel(
    ChannelConfig(
        label = "chat",
        ordered = true,
        reliable = true
    )
)
```

You can create multiple channels on the same `KPeer`:

```kotlin
val chat = peer.createChannel(ChannelConfig(label = "chat"))
val fileTransfer = peer.createChannel(
    ChannelConfig(
        label = "file-transfer",
        ordered = true,
        reliable = true
    )
)
val telemetry = peer.createChannel(
    ChannelConfig(
        label = "telemetry",
        ordered = false,
        reliable = false
    )
)
```

If a channel is created after the first offer/answer exchange, `KPeer` triggers renegotiation automatically on the initiator side.

## Discovering incoming channels

Both locally created channels and remotely announced channels are emitted through `peer.channels`:

```kotlin
scope.launch {
    peer.channels.collect { channel ->
        println("Channel available: ${channel.label}")
    }
}
```

You should subscribe to `peer.channels` early, especially on the answering side.

## Sending and receiving data

Send bytes through the channel:

```kotlin
chatChannel?.send("hello".encodeToByteArray())
```

Receive bytes from one specific channel:

```kotlin
scope.launch {
    chatChannel?.bytes?.collect { bytes ->
        println(bytes.decodeToString())
    }
}
```

Receive text from one specific channel:

```kotlin
scope.launch {
    chatChannel?.text?.collect { message ->
        println(message)
    }
}
```

Send text through the channel:

```kotlin
chatChannel?.send("hello")
```

Observe channel state:

```kotlin
scope.launch {
    chatChannel?.state?.collect { state ->
        println("chat state: $state")
    }
}
```

## Connection state

The connection state is exposed at peer level:

```kotlin
scope.launch {
    peer.connectionState.collect { state ->
        println("connection state: $state")
    }
}
```

## Closing

Close a single channel:

```kotlin
chatChannel?.close()
```

Close the whole peer connection:

```kotlin
peer.close()
```

Dispose the peer and release the internally created scope:

```kotlin
peer.dispose()
```

`dispose()` only cancels the scope when that scope was created by `KPeerContext`.  
If you passed your own scope into `KPeerContext`, ownership stays with your app.

## Notes

- `KChannel` exposes separate streams for binary and text messages
- signaling delivery ordering is still your responsibility
- if both sides produce overlapping offers at the same time, there is no full perfect-negotiation strategy yet
- JVM and Linux stubs are present, but WebRTC transport is implemented for the supported native platforms only
