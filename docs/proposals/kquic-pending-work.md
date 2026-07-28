# KQuic: trabajo pendiente

Este documento recoge lo que queda después de separar la fachada experimental `KQuicPeer`, el
signaling, la conectividad, el core QUIC y el driver UDP.

## Disponible en el prototipo

- Transporte real en Android 24+, iOS 16+ y macOS ARM64, con sockets UDP no bloqueantes y cierre
  idempotente.
- Gathering interno de candidatos host y server-reflexive IPv4/IPv6. Los Binding STUN salen por el
  mismo socket que conserva el candidato.
- Connectivity checks STUN autenticados, retransmisión acotada, roles derivados de `initiator`,
  nominación por el iniciador y selección determinista de ruta.
- Core determinista sin sockets ni reloj implícito, con espacios de packet number, handshake,
  canales, `QuicFlowController` y lifecycle separados. `QuicConnectionCore` recibe comandos,
  datagramas y tiempo explícito; produce efectos/eventos tipados que ejecuta `QuicIoDriver`.
- QUIC v1 Initial/Handshake/1-RTT con AES-128-GCM, header protection, TLS 1.3 mutuo,
  transport parameters y `HANDSHAKE_DONE`.
- Streams fiables con framing incremental y flow control, más QUIC DATAGRAM para canales
  `UNRELIABLE`.
- Límites de memoria y colas, backpressure en la entrada, idle timeout, cierre local/remoto y
  métricas de RTT, pérdida, congestión, bytes en vuelo, PTO, streams bloqueados y datagrams
  descartados.
- Vectores RFC 9001/NIST idénticos en Android host e iOS Simulator; red determinista con
  pérdida, duplicación, reordenación y MTU de 1.200 bytes; loopback UDP real con payload
  fragmentado, streams y datagrams.
- Lifecycle explícito: el constructor y `createChannel()` no realizan I/O; `start()` es
  thread-safe e idempotente y una oferta auto-inicia únicamente al respondedor.
- Presupuesto automatizado de 7.400 LOC Kotlin de producción, sin contar líneas vacías ni
  comentarios. `KQuicPeer`, `KQuicConnection` y `QuicConnectionCore` respetan además sus límites
  individuales.

## Pendiente antes de integrar en `kerobit-sdk`

1. Ejecutar la aceptación Android físico ↔ iPhone físico en una LAN.
2. Ejecutar la aceptación mediante candidatos server-reflexive en NAT compatibles.
3. Verificar intercambio bidireccional fiable/no fiable, payload grande, cierre limpio y rechazo de
   identidad incorrecta en ambos escenarios. El procedimiento y la tabla de evidencia están en
   `samples/kquic-mobile-harness/README.md`.
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
