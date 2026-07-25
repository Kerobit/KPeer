# KQuic

`KQuic` es el transporte QUIC P2P experimental de KPeer. Su API pública conserva el modelo pequeño
de `KPeer`: un peer, señales aportadas por la aplicación y canales etiquetados para bytes o texto.

```kotlin
val peer = KQuicPeer(
    context = KQuicContext(
        scope = scope,
        platformContext = androidContext // null en Apple
    ),
    config = KQuicConfig(
        initiator = true,
        rendezvousId = roomId,
        security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
        gatherHostCandidates = true,
        connectionTimeoutMs = 15_000
    )
)

peer.onSignal { signaling.send(it) }
peer.onConnectionState { println("QUIC: $it") }
peer.onChannel { channel ->
    channel.onBytes(::handleBytes)
    channel.onText(::handleText)
}

peer.start()

// En el receptor de la señalización:
peer.signal(remoteSignal)

// Cuando connectionState llegue a CONNECTED:
val reliable = peer.createChannel(
    KQuicChannelConfig("game-state", KQuicDelivery.RELIABLE_ORDERED)
)
val realtime = peer.createChannel(
    KQuicChannelConfig("position", KQuicDelivery.UNRELIABLE)
)

reliable.send(serializedState)
realtime.send(serializedPosition)

peer.close()
```

La señalización sigue siendo responsabilidad de la aplicación. KQuic intercambia ofertas,
respuestas, snapshots de candidatos y el pin SHA-256 de identidad, pero no abre WebSocket ni
adopta el protocolo de matchmaking de Kerobit. El helper de identidad efímera solo es seguro si
ese canal de señalización está autenticado.

Para certificados persistentes, PKI o pinning propio se utiliza:

```kotlin
KQuicSecurity.Explicit(
    identityProvider = KQuicIdentityProvider {
        KQuicIdentity(certificateChainDer, keystoreBackedSigner)
    },
    peerVerifier = KQuicPeerVerifier { remoteIdentity ->
        verifyRemoteIdentity(remoteIdentity)
    }
)
```

El signer solo expone `sign(algorithm, message)`: una clave de Android Keystore o Apple Keychain no
necesita ser exportable ni representarse mediante un handle de texto.

## Estado

La API pública, la identidad efímera P-256 Android/Apple, el rendezvous, el framing incremental y
los límites tienen pruebas. El núcleo común incluye parsing invariante previo a header protection,
protección completa de paquetes long/short, paquetes coalesced, tres espacios de packet number,
reensamblado CRYPTO/STREAM acotado, flow-control frames, QUIC DATAGRAM y recovery/NewReno
determinista. La máquina TLS común completa el perfil mutuo P-256/AES-128-GCM-SHA256, incluyendo
ALPN, parámetros QUIC, CertificateVerify y Finished; se prueba con JCA y Security.framework reales.

Todavía no están conectadas todas esas piezas mediante el actor UDP y falta AES-GCM/header
protection en el proveedor Apple. En consecuencia, el constructor público aún no establece una
conexión QUIC interoperable real. El módulo sigue siendo una base experimental y no debe anunciarse
como funcional ni estable hasta superar las pruebas Android↔iOS y Quinn.

El orden de implementación y los bloqueantes están en
[`docs/proposals/kquic-pending-work.md`](../docs/proposals/kquic-pending-work.md).
