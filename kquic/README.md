# KQuic

`KQuic` es el transporte QUIC P2P experimental de KPeer. Su API pública conserva el modelo pequeño
de `KPeer`: un peer, señales aportadas por la aplicación y canales etiquetados para bytes o texto.

```kotlin
val peer = KQuicPeer(
    context = KQuicContext(scope, platformContext),
    config = KQuicConfig(
        initiator = true,
        rendezvousId = roomId,
        credentialsProvider = credentials,
        peerVerifier = verifier
    )
)

peer.onSignal { signaling.send(it) }
peer.onChannel { channel ->
    channel.onBytes(::handleBytes)
}

peer.start()

// En el receptor de la señalización:
peer.signal(remoteSignal)

// Cuando connectionState llegue a CONNECTED:
val game = peer.createChannel(KQuicChannelConfig("game"))
game.send(payload)
```

La señalización sigue siendo responsabilidad de la aplicación. KQuic intercambia ofertas,
respuestas y candidatos, pero no abre WebSocket ni adopta el protocolo de matchmaking de Kerobit.

## Estado

La fachada, el ciclo de canales y el framing incremental ya tienen pruebas multiplataforma. La base
incluye sockets UDP para JVM/Android, cabeceras largas QUIC v1 y protección de paquetes Initial
validada con vectores RFC 9001. Todavía no están conectadas esas piezas a un handshake TLS 1.3
completo, por lo que el constructor público no establece aún conexiones de red reales y el módulo
no debe publicarse como versión estable.

El orden de implementación y los bloqueantes están en
[`docs/proposals/kquic-pending-work.md`](../docs/proposals/kquic-pending-work.md).
