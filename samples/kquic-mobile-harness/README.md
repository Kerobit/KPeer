# Harness manual KQuic Android/iOS

`MobileHarnessController.kt` contiene la lógica compartida mínima para probar dos dispositivos. No
define UI ni adopta un backend de signaling: la app anfitriona entrega una función autenticada
`sendSignal` y llama a `receiveSignal` al recibir la señal remota.

## Montaje

1. Añade `com.kerobit:kquic` o el proyecto `:kquic` a una app Android y al framework iOS.
2. Copia el controlador al source set común de la app de prueba.
3. Construye `KQuicContext(scope)` en ambos sistemas. El runtime selecciona internamente la
   implementación Android o Apple; la API común no recibe un `android.content.Context`.
4. Configura el mismo `roomId`, un peer con `initiator=true` y otro con `false`.
5. Codifica todos los campos de `KQuicOffer`/`KQuicAnswer` en el signaling. Las credenciales y el
   fingerprint son sensibles: usa un canal autenticado, no los escribas en logs y no aceptes
   señales de otra sala.
6. Observa `lines` y `stats`; añade botones para `start`, `sendReliable(16384)`,
   `sendUnreliable("ping")` y `close`.

Android necesita:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

iOS necesita `NSLocalNetworkUsageDescription` para las pruebas LAN.

## Matriz manual

- Misma LAN sin STUN: conexión, 16 KiB fiable, mensajes no fiables en ambos sentidos y cierre.
- STUN configurado: verificar que `selectedCandidatePair` contiene un candidato
  `SERVER_REFLEXIVE` cuando la ruta NAT compatible lo requiera.
- Alterar un fingerprint antes de entregarlo: el receptor debe terminar en `FAILED`.
- Desconectar Wi‑Fi durante la sesión: en este prototipo se cierra/recrea el peer; no existe
  migración QUIC.

## Aceptación Android físico ↔ iPhone físico

Esta prueba requiere dos dispositivos y un canal de signaling autenticado. No debe marcarse como
superada a partir de emuladores, simuladores o loopback.

1. Instala builds `debug` con el mismo commit en un Android API 24+ y un iPhone iOS 16+.
2. Conecta ambos a la misma LAN, sin VPN, configura el mismo `roomId` y deja vacía la lista STUN.
3. En el Android crea el peer iniciador; antes de `start()` crea los canales `reliable` y
   `unreliable`. Comprueba en los logs de la app que no existe señal ni candidato hasta pulsar
   `start`.
4. En el iPhone crea únicamente el peer respondedor. Entrega la oferta sin pulsar `start`: debe
   auto-iniciarse, emitir una respuesta y aceptar los dos canales remotos.
5. Espera `CONNECTED` en ambos. Envía en los dos sentidos texto, 16 KiB y al menos 1 MiB por el
   canal fiable; verifica contenido y orden byte a byte.
6. Envía 100 mensajes numerados por el canal no fiable. Se permiten pérdidas, pero nunca
   duplicación generada por el framing ni entrega de un payload corrupto.
7. Registra `selectedCandidatePair`, bytes/paquetes, RTT, pérdida, PTO, `streamsBlocked`,
   `datagramsDropped` y `closeReason`, sin credenciales ni fingerprints completos.
8. Cierra primero el Android y comprueba `CLOSED` en ambos. Repite invirtiendo iniciador,
   plataforma que cierra y dirección de cada transferencia.
9. Repite con STUN en dos redes NAT compatibles. La ruta seleccionada debe incluir al menos un
   candidato `SERVER_REFLEXIVE` cuando la topología lo requiera.
10. Repite alterando un byte del fingerprint remoto: la conexión debe terminar en `FAILED`, sin
    abrir ningún canal.

Conserva como evidencia por ejecución:

| Campo | Valor |
| --- | --- |
| Commit y fecha | |
| Android: modelo, API y red | |
| iPhone: modelo, iOS y red | |
| Dirección/rol probado | |
| Canal fiable: tamaños y hashes | |
| Canal no fiable: enviados/recibidos | |
| Candidate pair y estadísticas finales | |
| Resultado de cierre/rechazo de identidad | |

La aceptación se considera completa solo cuando las cuatro combinaciones
Android/iPhone × iniciador/respondedor quedan registradas y no quedan sockets o tareas activas al
cerrar.
