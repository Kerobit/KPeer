# KQuic: transporte QUIC nativo para Android e iOS

**Estado:** prototipo P2P funcional (`:kquic`), pendiente de aceptación en dispositivos físicos

**Ámbito:** módulo `:kquic` dentro de KPeer; Android, iOS y escritorio nativo/JVM
**Decisión de partida:** implementar el protocolo QUIC en el proyecto; no enlazar Quinn, MsQuic, libp2p ni una pila QUIC del sistema como motor de producción.

## Resumen ejecutivo

`KPeer` está diseñado alrededor de WebRTC: SDP, ICE, candidatos, renegociación y `RTCDataChannel`/SCTP atraviesan su API e implementación. QUIC usa UDP con TLS 1.3 integrado y sus propias conexiones, streams, datagramas y control de congestión. Encajarlo como otro backend de `KPeer` implicaría trasladar conceptos de WebRTC que no aplican y crear una compatibilidad engañosa.

La opción adoptada es una segunda biblioteca, **`KQuic`**, como módulo Gradle `:kquic`. Tiene una experiencia similar a `KPeer` —crear peer, intercambiar señales de candidatos, abrir canales lógicos, enviar bytes/texto, observar estado y cerrar— pero con un contrato de red explícitamente QUIC. Los clientes podrán adoptar una u otra biblioteca según el caso de uso sin que `KPeer` pierda su semántica actual.

La implementación propia debe limitarse a la **pila de protocolo y sus máquinas de estado**. No se deben escribir primitivas criptográficas caseras: Android e iOS aportarán AEAD, HKDF, SHA-2, X25519, firmas, aleatoriedad segura y validación de certificados mediante sus proveedores criptográficos. Usarlos no convierte a la biblioteca en un wrapper de una implementación QUIC externa.

## Objetivos y límites

### Objetivos de la primera versión

- QUIC v1 P2P sobre UDP para Android 24+ e iOS 16+. JVM es el banco de pruebas del núcleo.
- TLS 1.3 dentro de QUIC, autenticación de servidor y *pinning* de clave pública o cadena de confianza configurable.
- Streams bidireccionales y unidireccionales, multiplexados y con *flow control*.
- Datagrams QUIC opcionales para mensajes no fiables y de baja latencia.
- Reconexión a nivel de aplicación y telemetría suficiente para depurar pérdidas, RTT y congestión.
- API Kotlin Multiplatform común, con una superficie Swift/Objective-C generada por el framework de iOS.

### Fuera del MVP

- WebTransport, HTTP/3, QPACK y compatibilidad HTTP.
- QUIC v2, 0-RTT, *connection migration*, ECN, PMTUD/DPLPMTUD, FEC y multipath.
- Reemplazar WebRTC o compartir sus tipos de señalización/ICE por defecto.
- Soporte JavaScript: los navegadores no exponen UDP ni QUIC IETF programable; KQuic no declara target JS.
- Construir PKI, relay o TURN propio.

QUIC sobre UDP **no resuelve por sí solo** el descubrimiento ni el cruce de NAT. KQuic reúne
candidatos host y server-reflexive con STUN y ejecuta connectivity checks autenticados sobre el
mismo socket. La aplicación intercambia `KQuicSignal`. No se admiten candidatos relay en esta fase;
si no hay ruta directa, la conexión falla de forma explícita.

## Comparación de alternativas

| Alternativa | Ventaja | Problema | Decisión |
| --- | --- | --- | --- |
| Añadir QUIC dentro de `KPeer` | Un único artefacto | Acopla QUIC a SDP/ICE y a `DataChannel`; hace difícil explicar sus garantías | No recomendada |
| `KQuic` como módulo `:kquic` con API semejante | Semántica limpia, publicación independiente y configuración Gradle compartida | Dos bibliotecas que documentar | **Adoptada** |
| Usar Quinn, MsQuic o una pila del sistema | Entrega más rápida | No cumple la implementación propia; ABI, tamaño y comportamiento quedan fuera de control | Solo banco de pruebas o referencia |
| Adaptar libp2p | Aporta abstracciones P2P | Es un stack mayor que QUIC y cambia el alcance | No para esta biblioteca |

Quinn, MsQuic y libp2p son referencias útiles para estudiar estructura de estado, recuperación de pérdidas, pruebas de interoperabilidad y observabilidad. No se copiará código ni se expondrán sus tipos; antes de consultar código concreto hay que revisar su licencia y conservar una matriz de procedencia.

## API pública implementada

La similitud es de ergonomía, no una falsa equivalencia de transporte. `KQuic` no usa
`KPeerSignal`, SDP ni `RTCDataChannel`.

```kotlin
val peer = KQuicPeer(
    context = KQuicContext(scope = scope, platformContext = androidContext),
    config = KQuicConfig(
        initiator = true,
        rendezvousId = roomId,
        security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
        stunServers = listOf(KQuicStunServer("stun.example.net")),
        idleTimeoutMs = 30_000
    )
)

peer.onSignal(signaling::send)
peer.start()
peer.signal(signalReceivedFromRemote)

val channel = peer.createChannel(
    KQuicChannelConfig(label = "game", delivery = KQuicDelivery.RELIABLE_ORDERED)
)
channel.send(bytes)

peer.channels.collect { incoming -> /* bytes/text/state */ }
peer.connectionState.collect { state -> /* CONNECTING, CONNECTED, ... */ }
```

Tipos principales:

- `KQuicPeer`: posee una conexión P2P, reúne candidatos, intercambia señales y expone estado,
  estadísticas, canales y cierre.
- `KQuicContext`: agrupa scope, contexto de plataforma y servicios internos.
- `KQuicOffer`/`KQuicAnswer`: candidatos, credenciales efímeras y fingerprint de identidad.
- `KQuicChannel`: adaptador de alto nivel para un stream o un flujo de datagrams; conserva `send(ByteArray)`, `send(String)`, callbacks/`Flow` y `close()` cercanos a `KChannel`.
- `KQuicChannelConfig`: etiqueta y modo de entrega. `RELIABLE_ORDERED` usa un stream; `UNRELIABLE` usa QUIC DATAGRAM. No se ofrecerá `reliable = true, ordered = false` hasta definir explícitamente su framing y semántica.

Cada canal necesita framing propio: `varint channel-id`, tipo de payload (bytes/texto/control) y longitud. En streams el parser debe tolerar que una cabecera o un mensaje llegue fragmentado; en datagrams cada unidad conserva límites de mensaje. La etiqueta no es un identificador de stream QUIC: se negocia en un mensaje de control autenticado al abrir el canal.

Una capa de compatibilidad futura puede adaptar `KQuicChannel` a la parte común de `KChannel`, pero únicamente tras documentar las diferencias de apertura, conexión y entrega. No formará parte del MVP.

## Arquitectura del repositorio

Crear el módulo `:kquic` dentro de KPeer, en vez de ampliar `:kpeer`:

```text
KPeer/
  kquic/
    src/commonMain/.../kquic/
      KQuicPeer.kt           # API pública P2P
      KQuicSignal.kt
      KQuicChannel.kt
      internal/quic/          # wire format y máquinas de estado
        packet/ frame/ tls/ recovery/ transport/
    src/androidMain/...       # UDP, crypto, reloj y conectividad Android
    src/appleMain/...         # UDP, crypto, reloj y conectividad Apple
    src/commonTest/...        # vectores RFC y simulador determinista
  docs/
```

El núcleo `commonMain` contendrá todo lo determinista: codificación/decodificación, IDs, ACK ranges, *flow control*, temporizadores, recuperación y transición de estados. Las capas `actual` se limitarán a:

- `UdpSocket`: bind, send, receive, cierre y dirección local/remota.
- `MonotonicClock` y planificador de temporizadores.
- `SecureRandom`.
- `CryptoProvider` y `CertificateVerifier`.
- política de ciclo de vida y, en una fase posterior, notificación de cambios de red.

La recepción UDP nunca ejecutará lógica de usuario. Copiará/arrendará el datagrama, lo entregará serialmente al actor de la sesión y liberará el búfer al terminar. Una única sesión no puede ser procesada en paralelo: los números de paquete, ACK y claves se actualizan en orden. El envío y las devoluciones a `Flow` se harán fuera del hilo de I/O, con presupuestos de memoria y límites de cola.

## Trabajo de protocolo requerido

La implementación se hará contra los RFC, no contra el comportamiento de otra biblioteca. El MVP requiere como mínimo [RFC 8999](https://www.rfc-editor.org/rfc/rfc8999), [RFC 9000](https://www.rfc-editor.org/rfc/rfc9000), [RFC 9001](https://www.rfc-editor.org/rfc/rfc9001) y [RFC 9002](https://www.rfc-editor.org/rfc/rfc9002).

### 1. UDP y demultiplexado

- Socket IPv4/IPv6 no bloqueante, bind explícito, resolución DNS fuera del actor y recepción por lotes cuando la plataforma lo permita.
- Generar y rotar Connection IDs; tabla `destinationConnectionId -> session` y manejo de paquetes para conexiones nuevas.
- Validar tamaños y cabeceras antes de reservar memoria. Aplicar límite de conexiones, paquetes por segundo y bytes en vuelo por IP.
- Implementar *stateless retry* y token firmado/expirable antes de aceptar trabajo costoso. Respetar el límite anti-amplificación de tres veces los bytes recibidos hasta validar la dirección.

### 2. Cabeceras, paquetes y protección

- Varints, long/short header, versiones soportadas y version negotiation.
- Packet number spaces separados: Initial, Handshake y Application Data; truncado y reconstrucción de números de paquete.
- Derivación de claves Initial, protección de cabecera y protección de payload para cada nivel de cifrado.
- Longitud máxima, coalescing permitido y rechazo seguro de paquetes malformados o reservados.

### 3. TLS 1.3 para QUIC

- Transportar CRYPTO frames y mantener la máquina de handshake TLS 1.3 sin registros TLS sobre TCP.
- ClientHello/ServerHello, extensiones QUIC transport parameters, key schedule, handshake/application secrets y `HANDSHAKE_DONE`.
- Certificado, nombre del servidor, validación de cadena y pinning; registrar la política exacta de confianza por conexión.
- No habilitar reanudación/PSK/0-RTT hasta que haya almacenamiento seguro de tickets, política anti-replay y pruebas específicas.
- Usar únicamente proveedores criptográficos revisados por plataforma; limpiar secretos y no exponerlos en logs ni excepciones.

### 4. Frames y streams

- `PADDING`, `PING`, `ACK`, `CRYPTO`, `CONNECTION_CLOSE`, `HANDSHAKE_DONE`, `NEW_CONNECTION_ID` y límites de transporte necesarios para el MVP.
- `STREAM`, `RESET_STREAM`, `STOP_SENDING`, `MAX_DATA`, `MAX_STREAM_DATA`, `MAX_STREAMS` y `DATA_BLOCKED`/`STREAM_DATA_BLOCKED`.
- Reensamblado fuera de orden por offset, FIN, lectura con presión de retorno y límites por stream/conexión.
- El adaptador de canal abre streams y negocia metadata con un stream de control; reservar límites para que ese stream no quede bloqueado por tráfico de aplicación.

Para `KQuicDelivery.UNRELIABLE` se implementará [RFC 9221](https://www.rfc-editor.org/rfc/rfc9221): frame `DATAGRAM`, negociación de `max_datagram_frame_size`, MTU máximo expuesto y descarte cuando no haya presupuesto. Si el par no negocia la extensión, abrir ese canal debe fallar de forma explícita, nunca degradar silenciosamente a stream fiable.

### 5. Fiabilidad, congestión y ciclo de vida

- ACK delay y ACK ranges; mapa de paquetes enviados con frames reemitibles.
- Detección de pérdidas por umbral de paquetes y tiempo, RTT suavizado, PTO y retransmisión de CRYPTO/STREAM/control.
- Implementar NewReno conforme a RFC 9002 antes de considerar CUBIC o BBR. Limitar bytes en vuelo, *pacing* y tamaño inicial de datagrama conservador.
- Idle timeout, *handshake timeout*, cierre inmediato, draining y códigos de error de transporte/aplicación tipados.
- `CONNECTION_CLOSE` idempotente y liberación determinista de timers, buffers, streams, claves y socket compartido.

`PATH_CHALLENGE`/`PATH_RESPONSE`, migración de conexión y cambio Wi-Fi/celular se posponen. En el MVP se notificará `NETWORK_CHANGED`, se cerrará limpiamente la sesión y la aplicación decidirá si reconecta.

## Integración de plataforma

### Android

- Mantener `minSdk = 24`; documentar `android.permission.INTERNET` y no asumir un puerto local fijo.
- Implementar `UdpSocket` sobre APIs de datagrama no bloqueantes del runtime Android. El hilo de I/O no debe depender de `Dispatchers.Main`.
- Seguir la red activa con `ConnectivityManager`; cuando haya varias redes, permitir asociar el socket a la red elegida y exponer el cambio al núcleo.
- Considerar Doze, restricciones de ejecución en segundo plano y el uso de un foreground service para sesiones que realmente deban sobrevivir en background. Eso es responsabilidad de la aplicación, no de la librería.
- Usar las APIs criptográficas del sistema/Conscrypt disponible en el dispositivo tras una matriz de compatibilidad para AES-GCM, ChaCha20-Poly1305, HKDF y X25519.

### iOS

- Mantener los targets actuales (`iosArm64`, `iosSimulatorArm64`, `iosX64`) y el deployment target de KQuic separado del de KPeer si los requisitos de crypto lo exigen.
- Usar sockets UDP POSIX mediante Kotlin/Native y un monitor de ruta de red. No usar `NWProtocolQUIC`: ese sería el motor QUIC de Apple, incompatible con la decisión de implementar la pila propia.
- Implementar los adaptadores de Keychain/Security/CryptoKit que correspondan para claves, aleatoriedad y verificación de certificados, sin almacenar secretos en preferencias ni logs.
- Validar en dispositivo físico redes Wi‑Fi, celular y cambios de red. El comportamiento en background debe documentarse junto con los modos de background que la app declare; KQuic no solicitará entitlements por sí sola.

## Descubrimiento, autenticación y P2P

La aplicación entrega ofertas y respuestas entre dos `KQuicPeer`. KQuic administra sockets,
candidatos host/server-reflexive, retransmisión STUN, roles, nominación y selección del primer par
válido. El signaling debe autenticar el fingerprint y las credenciales efímeras; no se registran
contraseñas ni fingerprints completos. Cuando NAT simétrico, CGNAT o firewall impidan una ruta
directa será necesario un relay futuro; no se promete conectividad P2P universal.

## Seguridad, límites y observabilidad

- Validar cada longitud, varint, offset y límite de stream antes de indexar o asignar memoria.
- Imponer límites configurables de conexiones, streams, bytes reensamblados, datagrams, paquetes pendientes y CPU por tick.
- Aplicar *rate limits* a Retry, version negotiation y cierres; evitar oráculos que distingan tokens, claves o certificados.
- Prohibir logs de payloads, tokens, Connection IDs completos, claves, certificados privados o secretos TLS. Incluir un `connectionTraceId` aleatorio para correlación.
- Exponer `KQuicStats`: RTT, RTT mínimo, bytes/paquetes enviados y recibidos, pérdidas, PTO, congestión, bytes en vuelo, datagrams descartados, streams bloqueados y motivo de cierre.
- Añadir trazas estructuradas opcionales y un formato de captura redaccionado para reproducir fallos sin material secreto.

## Plan de entrega y puertas de calidad

| Hito | Entregable | Puerta de salida |
| --- | --- | --- |
| 0. Contrato | Repo, API experimental, decisiones de confianza, límites y modelo de amenazas | Revisión de arquitectura y seguridad aprobada |
| 1. Núcleo sin red | Varints, headers, frames, transport parameters y simulador determinista | Vectores RFC y fuzzing de parser en CI |
| 2. Handshake | Initial/Handshake, TLS 1.3 QUIC y certificado/pinning | Conexión mutua local y rechazo de cadena/pin inválidos |
| 3. Streams | Flow control, ACK, recovery, PTO, NewReno y cierre | Transferencia fiable bajo pérdida, reordenación y duplicados simulados |
| 4. Móvil | UDP/crypto Android+iOS, muestras y métricas | Pruebas en dispositivos físicos y ciclo de vida controlado |
| 5. Interop | Cliente/servidor de referencia y red adversa | Trabajo posterior; no bloquea el prototipo móvil |
| 6. Datagrams | RFC 9221, límites de MTU y canal no fiable | Pérdida explícita y sin retransmisión bajo congestión |
| 7. Beta | Auditoría externa, *load test*, documentación y versionado | Presupuesto de memoria/CPU definido y cero fallos críticos abiertos |

La interoperabilidad con Quinn y MsQuic y la revisión criptográfica externa siguen siendo puertas
para una versión estable, pero no bloquean este prototipo ni la siguiente integración experimental.

## Inventario inicial de trabajo

1. Crear `KQuic` como build Gradle KMP independiente, con los mismos targets móviles que KPeer y publicación separada (`com.kerobit:kquic`).
2. Definir API experimental, códigos de error, límites por defecto, política de confianza y compatibilidad Swift.
3. Construir el simulador de red y la batería de vectores antes de abrir un socket real.
4. Implementar el núcleo por capas: bytes/varints → paquetes → crypto/TLS → frames/streams → recovery/congestion.
5. Añadir adaptadores Android e iOS y pruebas de dispositivo, no solo emulador/simulador.
6. Construir un servidor de prueba mínimo y jobs de interoperabilidad con implementaciones externas.
7. Implementar el framing de canal y las muestras; solo entonces valorar un adaptador de ergonomía con `KPeer`.
8. Abrir una propuesta posterior para rendezvous/NAT traversal P2P, relay y migración de red.

## Criterios de aceptación del MVP

- Un Android físico y un iPhone físico se conectan directamente con identidad mutua, intercambian
  streams y datagrams en ambos sentidos y cierran sin fugas.
- Los streams conservan orden y fiabilidad bajo los escenarios de pérdida/reordenación de la matriz de pruebas.
- Los datagrams negociados pueden perderse, no se retransmiten y respetan el tamaño anunciado.
- Un certificado, pin, token Retry, frame o parámetro de transporte inválido se rechaza sin bloqueo ni crecimiento no acotado de memoria.
- Las métricas y el motivo de cierre permiten diagnosticar un fallo sin registrar secretos.
- El mismo núcleo supera los vectores en JVM, Android e iOS; Android e iOS pasan la matriz de redes física acordada.

## Decisiones adoptadas

- KQuic vive como módulo `:kquic` y se publica aparte de `:kpeer`.
- El primer caso de uso es P2P directo; relay/TURN queda fuera de esta fase.
- Las credenciales TLS y la verificación del remoto entran mediante proveedores inyectables. KQuic no adopta identidad, certificados ni contratos de Kerobit.
- La versión inicial cubre QUIC v1 y DATAGRAM, sin 0-RTT, migración, multipath, ECN, QUIC v2 ni JavaScript.
- La API Swift inicial será la superficie Objective-C/Kotlin generada por el framework; una envoltura Swift idiomática se evaluará después de estabilizar la API experimental.
