# KPeer

`KPeer` is a Kotlin Multiplatform WebRTC transport focused on peer-to-peer data channels.

It provides:

- one WebRTC `PeerConnection` per peer
- multiple `DataChannel`s on the same connection
- signaling primitives (`Offer`, `Answer`, `IceCandidate`)
- `Flow`-based APIs
- callback-based APIs
- automatic renegotiation when new channels are added later

`KPeer` does not include a signaling server. You exchange `KPeerSignal` messages with your own transport.

## Install

Current library coordinates:

```kotlin
implementation("com.kerobit:kpeer:1.0.0")
```

### Android

In a Kotlin Multiplatform project:

```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation("com.kerobit:kpeer:1.0.0")
        }
    }
}
```

In a plain Android module:

```kotlin
dependencies {
    implementation("com.kerobit:kpeer:1.0.0")
}
```

### iOS

In Kotlin Multiplatform:

```kotlin
kotlin {
    sourceSets {
        iosMain.dependencies {
            implementation("com.kerobit:kpeer:1.0.0")
        }
    }
}
```

If you expose your shared KMP framework to Swift, `KPeer` is then consumed through that framework on iOS.

### JS

The library includes a Kotlin/JS browser target backed by the native browser WebRTC APIs.

In Kotlin Multiplatform:

```kotlin
kotlin {
    sourceSets {
        jsMain.dependencies {
            implementation("com.kerobit:kpeer:1.0.0")
        }
    }
}
```

This JS target is intended for browser environments where `RTCPeerConnection` and `RTCDataChannel` are available.

## Core concepts

- `KPeer` owns the peer connection and signaling lifecycle
- `KChannel` owns sending and receiving messages for one data channel
- `KPeerSignal` is the payload exchanged through your signaling backend
- `KSubscription` is a small cancellable handle for callback subscriptions

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

If you already have your own application scope:

```kotlin
val peer = KPeer(
    context = KPeerContext(
        scope = scope,
        platformContext = androidContext
    ),
    config = KPeerConfig(
        initiator = true
    )
)
```

`initiator = true` creates the first offer.  
`initiator = false` waits for the first remote offer.

## Signaling

You must deliver emitted signals to the remote peer:

```kotlin
scope.launch {
    peer.signals.collect { signal ->
        signalingBackend.send(signal)
    }
}
```

And apply remote signaling messages like this:

```kotlin
suspend fun onRemoteSignal(signal: KPeerSignal) {
    peer.signal(signal)
}
```

Supported signal types:

- `KPeerSignal.Offer`
- `KPeerSignal.Answer`
- `KPeerSignal.IceCandidate`

## Creating channels

```kotlin
val chatChannel = peer.createChannel(
    ChannelConfig(
        label = "chat",
        ordered = true,
        reliable = true
    )
)
```

Multiple channels can live on the same peer connection:

```kotlin
val chat = peer.createChannel(ChannelConfig(label = "chat"))
val telemetry = peer.createChannel(
    ChannelConfig(
        label = "telemetry",
        ordered = false,
        reliable = false
    )
)
val fileTransfer = peer.createChannel(
    ChannelConfig(
        label = "file-transfer"
    )
)
```

If a new channel is created after the initial connection is already established, `KPeer` triggers renegotiation automatically on the initiator side.

## Flow API

Discover channels:

```kotlin
scope.launch {
    peer.channels.collect { channel ->
        println("Channel available: ${channel.label}")
    }
}
```

Forward signaling:

```kotlin
scope.launch {
    peer.signals.collect { signal ->
        signalingBackend.send(signal)
    }
}
```

Observe peer connection state:

```kotlin
scope.launch {
    peer.connectionState.collect { state ->
        println("connection state: $state")
    }
}
```

Receive channel messages:

```kotlin
scope.launch {
    chatChannel?.bytes?.collect { bytes ->
        println("bytes: ${bytes.size}")
    }
}

scope.launch {
    chatChannel?.text?.collect { message ->
        println("text: $message")
    }
}
```

Observe channel state:

```kotlin
scope.launch {
    chatChannel?.state?.collect { state ->
        println("channel state: $state")
    }
}
```

Send messages:

```kotlin
chatChannel?.send("hello")
chatChannel?.send("hello".encodeToByteArray())
```

## Callback API

If you prefer a callback-oriented API, you can subscribe directly:

```kotlin
val signalSubscription = peer.onSignal { signal ->
    signalingBackend.send(signal)
}

val stateSubscription = peer.onConnectionState { state ->
    println("peer state: $state")
}

val channelSubscription = peer.onChannel { channel ->
    println("channel available: ${channel.label}")

    channel.onText { message ->
        println("text message: $message")
    }

    channel.onBytes { bytes ->
        println("bytes received: ${bytes.size}")
    }

    channel.onState { state ->
        println("channel state: $state")
    }
}
```

Every callback registration returns a `KSubscription`:

```kotlin
signalSubscription.cancel()
stateSubscription.cancel()
channelSubscription.cancel()
```

## Complete sample

This sample shows two peers in the same process. In a real application they would live in different devices or runtimes, and you would use websockets, HTTP, or another transport to exchange signaling messages.

```kotlin
import com.kerobit.kpeer.ChannelConfig
import com.kerobit.kpeer.KPeer
import com.kerobit.kpeer.KPeerConfig
import com.kerobit.kpeer.KPeerContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun sample(androidContext: Any?) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val peer1 = KPeer(
        context = KPeerContext(
            scope = scope,
            platformContext = androidContext
        ),
        config = KPeerConfig(initiator = true)
    )

    val peer2 = KPeer(
        context = KPeerContext(
            scope = scope,
            platformContext = androidContext
        ),
        config = KPeerConfig(initiator = false)
    )

    peer1.onSignal { signal ->
        scope.launch {
            peer2.signal(signal)
        }
    }

    peer2.onSignal { signal ->
        scope.launch {
            peer1.signal(signal)
        }
    }

    peer2.onChannel { channel ->
        channel.onText { message ->
            println("peer2 got text: $message")
        }

        channel.onBytes { bytes ->
            println("peer2 got bytes: ${bytes.size}")
        }
    }

    scope.launch {
        val chat = peer1.createChannel(ChannelConfig(label = "chat"))
        chat?.onState { state ->
            println("peer1 chat state: $state")
        }
        chat?.send("hello from peer1")
        chat?.send(byteArrayOf(1, 2, 3, 4))
    }
}
```

## Manual signaling sample

If you want a manual exchange flow similar to `simple-peer`, the model is:

1. create one initiator peer and one receiver peer
2. listen to `peer.onSignal { ... }`
3. print or serialize each `KPeerSignal`
4. paste or send that payload to the other side
5. call `peer.signal(remoteSignal)` on the receiving side
6. once negotiation finishes, create or receive channels and start sending messages

In practice:

```kotlin
peer.onSignal { signal ->
    println("OUTGOING SIGNAL: $signal")
}

scope.launch {
    val remoteSignal: KPeerSignal = readSignalFromSomewhere()
    peer.signal(remoteSignal)
}
```

## Closing and disposing

Close the peer connection only:

```kotlin
peer.close()
```

Dispose the peer and also dispose the internally created context scope:

```kotlin
peer.dispose()
```

`dispose()` only cancels the scope if that scope was created internally by `KPeerContext`. If you passed your own scope, ownership stays with your application.

## Notes

- `KChannel` exposes separate streams for binary and text messages
- the callback API is a thin wrapper over the `Flow` API
- signaling ordering and delivery are still the responsibility of your app
- there is still no full perfect-negotiation strategy for simultaneous overlapping offers
- JVM and Linux targets currently expose stubs; WebRTC transport is implemented on the supported native platforms
