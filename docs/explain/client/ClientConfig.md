# `ClientConfig` — parametri da file `.properties`

## Ruolo
Carica i parametri del client da un file `client.properties` (default
`client.properties`, o path passato come argomento). NO CLI/interattivo (§4).

## Campi
| Campo | Tipo | Chiave `.properties` | Default |
|-------|------|---------------------|---------|
| `host` | `String` | `host` | `localhost` |
| `tcpPort` | `int` | `port` | `12345` |

La porta **UDP non** è nel config: è la porta effimera del socket receiver,
legata a runtime e mandata nel `login` come `udpPort`.

## Metodi
- `load(path)` — legge il `Properties` con `try-with-resources` e costruisce il
  config. `static`.

## Collegamenti
- `client/ClientMain`: unico consumatore.