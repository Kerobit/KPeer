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
        stunServers = listOf(
            KQuicStunServer("stun.example.net")
        ),
        candidateGatheringTimeoutMs = 5_000,
        connectivityCheckTimeoutMs = 10_000,
        connectionTimeoutMs = 30_000
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

La señalización sigue siendo responsabilidad de la aplicación. KQuic intercambia una oferta y una
respuesta con candidatos, credenciales efímeras de connectivity checks y el pin SHA-256 de
identidad, pero no abre WebSocket ni adopta el protocolo de matchmaking de Kerobit. No hay
servidores STUN públicos por defecto. Las señales contienen una contraseña efímera y un fingerprint:
deben transportarse por un canal autenticado y nunca registrarse completas.

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

## Plataformas

Android requiere API 24 o superior y el permiso:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

En iOS 16 o superior, una app que busque peers en la LAN debe declarar
`NSLocalNetworkUsageDescription` con una explicación adecuada. KQuic no solicita permisos,
entitlements ni ejecución en background por la aplicación.

Los candidatos host se reúnen sobre sockets UDP reales. Cada `KQuicStunServer` ejecuta Binding
desde esos mismos sockets y puede añadir candidatos server-reflexive IPv4/IPv6. STUN no sustituye
TURN: NAT simétrico, CGNAT o políticas de firewall pueden impedir la conexión directa.

## Estado y límites

El constructor público usa un transporte UDP real en Android, iOS y JVM. El actor serial conecta
packet protection, TLS 1.3 mutuo, tres espacios de packet number, ACK/PTO/NewReno, flow control,
streams y QUIC DATAGRAM. `CONNECTED` solo se publica tras Finished, `HANDSHAKE_DONE`, parámetros
QUIC válidos y la verificación de identidad configurada. `send()` indica aceptación en la cola
local y puede devolver `false` ante backpressure.

Esta entrega sigue siendo un prototipo experimental. No incluye relay/TURN, migración de conexión,
0-RTT, QUIC v2, PMTUD, auditoría externa ni una promesa de interoperabilidad con Quinn/MsQuic.
Un cambio de ruta requiere cerrar y crear otro peer. La validación Android físico ↔ iPhone físico
continúa siendo una puerta manual antes de integrar el módulo en `kerobit-sdk`.

El payload de aplicación de un canal `UNRELIABLE` está limitado por defecto a 1.100 bytes para
mantener el datagrama QUIC completo dentro del MTU conservador de 1.200 bytes.

El orden de implementación y los bloqueantes están en
[`docs/proposals/kquic-pending-work.md`](../docs/proposals/kquic-pending-work.md).
