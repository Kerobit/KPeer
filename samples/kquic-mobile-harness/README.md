# Harness manual KQuic Android/iOS

`MobileHarnessController.kt` contiene la lógica compartida mínima para probar dos dispositivos. No
define UI ni adopta un backend de signaling: la app anfitriona entrega una función autenticada
`sendSignal` y llama a `receiveSignal` al recibir la señal remota.

## Montaje

1. Añade `com.kerobit:kquic` o el proyecto `:kquic` a una app Android y al framework iOS.
2. Copia el controlador al source set común de la app de prueba.
3. En Android pasa el `Context`; en iOS pasa `null`.
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
