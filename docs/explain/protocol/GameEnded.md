# `protocol/GameEnded` — notifica UDP di fine partita

## Ruolo
POJO condiviso (server → serializza, client → deserializza) per la notifica
asincrona UDP inviata a tutti i partecipanti quando una partita termina.
Sostituisce la costruzione a mano di `JsonObject` (svincola entrambi i lati
dal formato).

## Formato wire
```json
{"type":"GAME_ENDED","gameId":int,"roundId":int}
```

## Campi
| Campo | Tipo | Note |
|-------|------|------|
| `type` | String | sempre `"GAME_ENDED"` (il client filtra su questo) |
| `gameId` | int | id *sorgente* della partita (si ripete al wrap del loader) |
| `roundId` | int | id univoco/monotono, usato nelle richieste di esito |

## Note
È **solo un segnale**: non contiene i risultati. Il client, ricevutala, legge
l'esito via TCP (`requestGameInfo(roundId)`/`requestGameStats`). Se la notifica
arriva prima di `finalizeGame` si apre una finestra TOCTOU: il client deve
ritentare la lettura dello storico.

## Collegamenti
- `network/GameScheduler`: costruisce `new GameEnded("GAME_ENDED", gameId, roundId)`.
- `network/UdpNotifier`: `notifyEnd(parts, payload)` lo serializza (signature `Object`).
- `client/UdpClient`: `GSON.fromJson(json, GameEnded.class)` + filtro su `type`.