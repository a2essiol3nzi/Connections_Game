# Documentazione componente: `loader/GameLoader` (caricamento partite pigro)

## Responsabilità
Carica il file JSON delle partite (fornito dai docenti) **senza tenerlo tutto
in RAM**. Fornisce accesso on-demand a una singola partita (`gameAt(i)`) e un
generatore lazy (`stream()`).

## Perché è pigro (risposta alla critica)
Le due versioni precedenti tenevano l'intero file in memoria (Data Binding, o
streaming che accumulava in una `List`). Qui invece:
- all'avvio si **indicizzano gli "span"** (offset inizio/fine in byte) di ogni
  oggetto-partita tramite `RandomAccessFile` (I/O NON bufferizzato → la
  posizione su file è esatta; un `InputStreamReader` bufferizzato avrebbe
  falsato i conteggi);
- `gameAt(i)` legge SOLO i byte di quell'oggetto e lo parsia con Gson (Data
  Binding limitato a UN oggetto) → memoria costante;
- `stream()` espone un `Stream<GameData>` che produce le partite una alla volta.

## Scansione
Il file è `[ {…}, {…}, … ]`. La scansione cerca ogni oggetto top-level
bilanciando le parentesi graffe `{ }` (ignorando quelle dentro le stringhe, con
gestione dell'escape `\"`). Per ogni oggetto si registra `[start, end)`.

## Schema del file (confermato)
Array top-level di `{"gameId":int, "groups":[{"theme":str,"words":[4 str]}]}`.
`theme` è la categoria NASCOSTA (mai inviata al client).

## IO
`RandomAccessFile` "r" + `readFully` per il blocco, charset UTF-8 esplicito nel
`fromJson(String)`.

## Collegamenti
- `model/GameData`, `model/GameData.Group`: POJO di destinazione.
- `core/GameManager`: chiama `gameAt(idx)` a ogni rotazione (indice ciclico).
- `core/ServerMain`: costruisce il loader (solo indicizzazione).
