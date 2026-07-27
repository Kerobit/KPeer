# KQuic: trabajo pendiente

Este documento recoge lo que queda después de conectar la fachada experimental `KQuicPeer` con el
transporte UDP y el actor QUIC común.

## Disponible en el prototipo

- Transporte real en Android 24+, iOS 16+ y JVM, con sockets UDP no bloqueantes y cierre
  idempotente.
- Gathering interno de candidatos host y server-reflexive IPv4/IPv6. Los Binding STUN salen por el
  mismo socket que conserva el candidato.
- Connectivity checks STUN autenticados, retransmisión acotada, roles derivados de `initiator`,
  nominación por el iniciador y selección determinista de ruta.
- Actor serial propietario de Connection IDs, packet numbers, claves, ACK, recovery, streams,
  timers, congestión y estado.
- QUIC v1 Initial/Handshake/1-RTT con AES-128-GCM, header protection, TLS 1.3 mutuo,
  transport parameters y `HANDSHAKE_DONE`.
- Streams fiables con framing incremental y flow control, más QUIC DATAGRAM para canales
  `UNRELIABLE`.
- Límites de memoria y colas, backpressure en la entrada, idle timeout, cierre local/remoto y
  métricas de RTT, pérdida, congestión, bytes en vuelo, PTO, streams bloqueados y datagrams
  descartados.
- Vectores RFC 9001/NIST idénticos en JVM, Android host e iOS Simulator; red determinista con
  pérdida, duplicación, reordenación y MTU de 1.200 bytes; loopback UDP real con payload
  fragmentado, streams y datagrams.

## Pendiente antes de integrar en `kerobit-sdk`

1. Ejecutar la aceptación Android físico ↔ iPhone físico en una LAN.
2. Ejecutar la aceptación mediante candidatos server-reflexive en NAT compatibles.
3. Verificar intercambio bidireccional fiable/no fiable, payload grande, cierre limpio y rechazo de
   identidad incorrecta en ambos escenarios.
4. Añadir un monitor de ruta por plataforma. Como no se soporta migración, un cambio debe cerrar
   con `NETWORK_CHANGED` y dejar la reconexión a la aplicación.
5. Completar pruebas instrumentadas de Android API 24 y ciclos repetidos de creación/cierre para
   detectar fugas de sockets, jobs y handles criptográficos.

## Fuera de esta fase

- Relay/TURN y conectividad garantizada con NAT simétrico o CGNAT.
- Migración QUIC, 0-RTT, QUIC v2, ECN, multipath, PMTUD/DPLPMTUD y FEC.
- Interoperabilidad obligatoria con Quinn/MsQuic.
- Auditoría externa, fuzzing continuo y garantías de producción.
- Integración y negociación de transporte dentro de `kerobit-sdk`.

## Puertas para una versión estable

El prototipo no debe publicarse como estable hasta añadir relay si el producto lo exige,
interoperabilidad con implementaciones independientes, fuzzing sostenido de parsers, una matriz de
dispositivos/redes, presupuestos medidos de CPU/memoria y revisión externa del protocolo y la
criptografía.
