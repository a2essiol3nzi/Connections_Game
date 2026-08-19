# `loader/GameLoader` — sorgente partite PIGRA (O(1) memoria, streaming)

## Ruolo
Carica partite da un file JSON di **grandi dimensioni** senza tenerle tutte in
RAM. Approccio **generatore/iteratore**: all'avvio conta gli oggetti con una
sola passata sequenziale (`JsonReader` + `skipValue`); `cyclicIterator()`
espone un iteratore *lazy* che legge **UN oggetto alla volta** via `JsonReader`
e, arrivato in fondo al file, riapre il file e ricomincia (ciclo infinito).
Memoria O(1) rispetto ai corpi delle partite: Gson fa data-binding di un solo
oggetto per volta; il file non viene mai letto tutto insieme.

## Stato interno
- `path` — percorso del file partite.
- `total` — numero partite (contate a costruzione).
- `GSON` — istanza `Gson` (condivisa).

## Metodi principali
- `int total()` — numero partite disponibili.
- `Iterator<GameData> cyclicIterator()` — iteratore **lazy e ciclico
  all'infinito**: ogni `next()` parsia un solo oggetto; a fine array il reader
  viene chiuso, riaperto e riposizionato (`beginArray()`) ricominciando dal
  primo. `hasNext()` ritorna **sempre `true`** (ciclo infinito).
- `countGames()` (privato) — passata sequenziale: `beginArray()` + ciclo
  `while(hasNext()) skipValue()`; conta SOLO gli oggetti di primo livello
  (Gson bilancia lo skip anche se annidato).
- `open()` (privato) — apre `JsonReader` UTF-8 da `FileInputStream` (streaming,
  non bufferizzato in RAM).

## Note tecniche
- JSON di partite = array top-level `[{gameId, groups:[{theme, words:[4]}]}]`.
  `theme` non serve qui (lo usa `ActiveGame`).
- Niente più indicizzazione ad offset né scansione byte-a-byte: il parsing
  strutturale è delegato interamente a Gson `JsonReader`.
- **Ordine sequenziale (non casuale)**: le partite sono servite nell'ordine
  del file, una dopo l'altra, a ciclo. `# ponytail: prevedibilità — un
  osservatore che gioca regolarmente può anticipare la partita successiva; se
  serve imprevedibilità, reintrodurre una permutazione (shuffle indici o del
  file in lettura).` L'interfaccia pubblica è invariata rispetto alla versione
  precedente ⇒ nessun caller da modificare.

## Collegamenti
- `model/GameData`: tipo prodotto da `next()`.
- `core/GameManager`: consuma `cyclicIterator()` (ignora `hasNext()`, chiama
  direttamente `next()` dentro il try/catch di `makeNext`).
- `core/ServerMain`: costruisce il loader e ne stampa `total()`.
