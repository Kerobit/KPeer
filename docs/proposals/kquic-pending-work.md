# KQuic: trabajo pendiente

Este documento recoge lo necesario para pasar de la base actual de `:kquic` a una biblioteca QUIC P2P interoperable y apta para producción.

## Base ya disponible

- Módulo Gradle publicable `:kquic`, sin dependencia de WebRTC ni target JS.
- API experimental: `KQuicPeer`, señales de rendezvous y candidatos, canales, estados, estadísticas, límites y proveedores de credenciales/verificación inyectables.
- Códecs comunes para varints, parámetros de transporte y frames `ACK`, `CRYPTO`, `STREAM`, `DATAGRAM` y cierre.
- Ledger básico de paquetes enviados y recuperación determinista.
- Selección determinista inicial de pares de candidatos y tests multiplataforma del núcleo.
- Frontera interna `KQuicTransport` entre la API pública y el motor QUIC, con conexión autenticada,
  eventos de canal, datos, cierre, errores y estadísticas sin exponer detalles de plataforma.
- Ciclo completo de `KQuicPeer` sobre un transporte determinista: conexión con timeout, verificación
  del remoto, apertura de canal, bytes/texto, cierre remoto y liberación de coroutines.
- Framing incremental y acotado de canales sobre streams: apertura (`label` y modo de entrega),
  bytes, texto y cierre, tolerando cualquier fragmentación de lectura.
- Códec de cabeceras largas QUIC v1 (`Initial`, `0-RTT` y `Handshake`) y reconstrucción/truncado
  de números de paquete según RFC 9000.
- Frontera UDP común y sockets de datagramas IPv4/IPv6 para JVM y Android, con bind a puerto
  efímero, envío/recepción suspendible, límite de payload y cierre idempotente. La prueba JVM usa
  dos sockets UDP reales sobre loopback.
- Derivación HKDF de claves Initial cliente/servidor, AES-128-GCM y protección de cabecera mediante
  los proveedores JCA de JVM/Android. Los vectores de claves y máscara se validan contra RFC 9001.

La fachada pública no abre aún los sockets disponibles ni completa un handshake QUIC real. Ya puede
ejecutar y probar el ciclo P2P completo cuando recibe un `KQuicTransport`, pero el constructor
público usa un driver no disponible hasta que exista el pipeline de protocolo. Por tanto, una
aplicación real permanece en `CONNECTING` y termina en `FAILED` con `TransportUnavailable`; el
transporte determinista es exclusivamente una prueba de contrato, no interoperabilidad QUIC.

## Bloqueantes para una conexión QUIC real

1. Completar los drivers UDP detrás de `KQuicTransport`: integrar los sockets JVM/Android ya
   disponibles, añadir Apple/Linux/Windows, temporizador monotónico, aleatoriedad segura, control
   de buffers y ciclo de vida.
2. Completar el formato de paquetes QUIC v1: long/short headers, Connection IDs, token Retry, versiones, packet number spaces, coalescing y reconstrucción de números de paquete.
3. Integrar la derivación y protección Initial ya implementada en el pipeline de paquetes; añadir
   actualización/descarte de claves y protección para Handshake y Application Data a partir de los
   secretos entregados por TLS.
4. Implementar TLS 1.3 adaptado a QUIC: CRYPTO reassembly, ClientHello/ServerHello, extensiones de parámetros de transporte, certificados, Finished y `HANDSHAKE_DONE`.
5. Conectar `KQuicCredentialsProvider` y `KQuicPeerVerifier` al handshake real usando proveedores criptográficos seguros de cada plataforma; no implementar primitivas criptográficas propias.
6. Hacer que el motor llame al contrato ya existente solo tras validar TLS y parámetros de transporte;
   `KQuicPeer` aplicará entonces `KQuicPeerVerifier`, pasará a `CONNECTED` y habilitará canales y `send()`.

## Streams, congestión y cierre

1. Implementar todos los frames necesarios para streams y control de flujo: `MAX_DATA`, `MAX_STREAM_DATA`, `MAX_STREAMS`, `DATA_BLOCKED`, `STREAM_DATA_BLOCKED`, `RESET_STREAM` y `STOP_SENDING`.
2. Añadir asignación de stream IDs, reensamblado por offset, FIN, presión de retorno y framing de canal (`label`, bytes/texto/control) sobre streams.
3. Completar ACK ranges, retraso de ACK, detección temporal de pérdida, PTO, retransmisión de frames reemitibles y NewReno conforme a RFC 9002.
4. Aplicar *pacing*, límites de bytes en vuelo y límites de memoria por conexión/stream/datagram.
5. Implementar `CONNECTION_CLOSE`, draining, idle timeout y códigos de error de transporte/aplicación; liberar sockets, timers, claves y buffers de forma determinista.
6. Negociar RFC 9221 mediante `max_datagram_frame_size`; rechazar canales `UNRELIABLE` si el peer no negocia DATAGRAM.

## P2P, NAT y relay

1. Implementar gathering de candidatos host y server-reflexive con STUN, además de la recolección desde adaptadores de plataforma.
2. Implementar connectivity checks ICE-compatible, prioridades, pares en espera/en progreso, consentimiento y selección de ruta tras una comprobación satisfactoria, no solo por ranking.
3. Definir el documento de protocolo para el relay: asignación, token opaco, expiración, límites, autenticación del plano de control, reenvío UDP y errores.
4. Implementar el cliente de candidatos relay en KQuic. El servicio relay y su operación permanecen fuera de este repositorio.
5. Cubrir NAT cono, restringido, simétrico/CGNAT, pérdida de ruta y fallback relay. La migración de red queda fuera de v1 y debe provocar reconexión controlada.

## Calidad, seguridad y entrega

1. Añadir vectores RFC de paquetes y TLS, pruebas de propiedad/fuzzing de todos los parsers y simulador determinista de red con pérdida, duplicados, reordenación, corrupción, MTU y latencia.
2. Añadir pruebas de handshake, certificados inválidos, pin/verificador rechazado, anti-amplificación, Retry, límites de recursos, cierre y fugas.
3. Crear pruebas de integración Android e iOS en dispositivo físico y una matriz de redes Wi-Fi/celular.
4. Interoperar bidireccionalmente con Quinn y MsQuic antes de beta; la interop es una puerta de calidad, no una dependencia de producción.
5. Exponer métricas finales de RTT, pérdida, congestión, bytes en vuelo, streams bloqueados, datagrams descartados, ruta seleccionada y motivo de cierre, sin registrar secretos ni tokens.
6. Documentar límites por defecto, compatibilidad de versiones, modelo de amenazas, especificación del relay y ejemplos de señalización.
7. Someter el parser, el handshake y el modelo criptográfico a revisión de seguridad externa antes de una release estable.

## Orden recomendado

1. UDP/plataforma y paquetes Initial protegidos.
2. TLS 1.3, handshake y conexión cliente-servidor de laboratorio.
3. Streams, ACK/loss recovery, control de flujo y cierre.
4. DATAGRAM y métricas.
5. STUN, checks P2P y candidato relay.
6. Fuzzing, simulador adverso, pruebas físicas e interoperabilidad.
7. Especificación de relay, auditoría y beta.

No se debe declarar KQuic funcional para producción ni publicar una versión estable hasta completar los bloques de conexión real, seguridad, P2P/fallback y calidad.
