# pinpad-protocol-kt

Protocolo entre la **caja (ECR)** y el **PINpad**, con su cliente y un
**simulador de PINpad** al que se le pueden inyectar fallos.

Es el modelo semi-integrado: la caja manda el monto y el PINpad devuelve la
tarjeta leída con el PIN ya cifrado con DUKPT. La caja nunca ve el PIN ni
tiene una llave: cuando necesita firmar un mensaje para el host, se lo pide al
PINpad.

## La trama

```
STX | datos (campos separados por FS 0x1C) | ETX | LRC
```

El LRC es el XOR de los datos y el ETX. Detecta ruido en el cable, no a un
atacante: la integridad de la transacción la da el MAC del mensaje ISO 8583.

| Orden | Respuesta |
| --- | --- |
| `SALE monto moneda stan` | `CARD modo pista2 secuencia emv ksn pinblock` o `FAIL motivo` |
| `NEXT_KSN` | `KSN ksn` |
| `MAC datos` | `MACRES ksn mac` |
| `STATUS` | `STATUS serial ksn` |
| `FAULT nombre valor` (solo laboratorio) | `OK` |

## Las reglas del cliente

1. Manda la trama y espera ACK. Con NAK o sin respuesta, la reenvía (hasta 3 veces).
2. Con ACK, el PINpad tiene la orden. Espera la respuesta hasta 30 s, que
   incluyen insertar la tarjeta y digitar el PIN.
3. Respuesta con LRC mal → NAK y el PINpad la reenvía.

Y lo que importa: **`NotDelivered`** significa que el PINpad nunca tuvo la
orden (seguro reintentar); **`NoResponse`** significa que la tuvo y no sabemos
qué hizo.

## Fallos que el simulador sabe provocar

`NAK`, `DROP_ACK` (ACK perdido), `CORRUPT` (LRC dañado en la respuesta),
`DELAY`, `DISCONNECT`, `CARD` (tarjeta retirada, cliente cancela), `PIN`
(otro PIN digitado), `CARD_BRAND` y `RESET`. Cada uno aplica "las próximas N
veces", así que las pruebas son deterministas.

## Cómo correrlo

```bash
./gradlew test   # 20 pruebas: tramas, reintentos y cada fallo contra el simulador
./gradlew run    # simulador por TCP en el puerto 9100 (o --args="9200")
```

Depende de `dukpt-kt` (cifrado del PIN) e `iso8583-kt` (Retail MAC). Si están
clonados al lado, se compilan desde el código.

## Lo que no es real, a propósito

- **El criptograma EMV (9F26) es aleatorio.** El simulador no tiene las llaves
  de la tarjeta, así que no puede producir un ARQC real y no lo intenta.
- **La pista 2 viaja en claro por el enlace local**, porque `dukpt-kt` todavía
  no tiene la variante de cifrado de datos. En un PINpad real va cifrada, o el
  PINpad habla directo con el host.
- Un PINpad real hace todo esto dentro de su procesador seguro certificado
  PCI PTS.
