# `loader/GameLoader` — sorgente partite PIGRA (O(1) memoria, streaming)

## Ruolo
Carica partite da un file JSON di **grandi dimensioni** senza tenerle tutte in
RAM. Approccio **generatore/iteratore**: all'avvio conta gli oggetti con una
sola passata sequenziale (`JsonReader` + `skipValue`); `cyclicIterator()`
espone un `CyclicGameIterator` *lazy* che legge **UN oggetto alla volta** via
`JsonReader` e, arrivato in fondo al file, riapre il file e ricomincia (ciclo
infinito). Memoria O(1): Gson fa data-binding di un solo oggetto per volta; il
file non viene mai letto tutto insieme.

**Robustezza a runtime**: un oggetto malformato (sintassi rotta) NON ferma il
server né impenna la CPU. Nell'iteratore, dopo `JsonSyntaxException` il reader
resta desincronizzato sul token rotto, quindi si **riapre** e si saltano gli
oggetti già emessi + quello rotto, riprendendo dal successivo. `# ponytail:
file interamente rotto -> spin con reopen per oggetto; non capita con il file
dei docenti (validato a startup da countGames).`

## Stato interno
- `path` — percorso del file partite.
- `total` — numero partite (contate a costruzione).
- `GSON` — istanza `Gson` (condivisa).

## Classi / metodi principali
- `int total()` — numero partite disponibili.
- `final class CyclicGameIterator implements Iterator<GameData>` — `hasNext()`
  ritorna **sempre `true`** (ciclico); `next()` parsia un oggetto, a fine array
  riapre e azzera `index` (wrap), su `JsonSyntaxException` riapre e salta
  `index + 1` (buoni + rotto). Mai spin/loop infinito. Lancia `UncheckedIOException`
  su errore I/O (Gson lo normalizza in `JsonIOException`).
- `cyclicIterator()` — ritorna un'istanza dell'iteratore.
- `countGames()` (privato) — `beginArray()` + `while(hasNext()) skipValue()`:
  conta SOLO oggetti di primo livello. Non gestisce entry malformate (un file
  corrotto a monte fa fallire la costruzione del loader).
- `open()` (privato) — `JsonReader` UTF-8 da `FileInputStream` (streaming).

## Note tecniche
- JSON = array top-level `[{gameId, groups:[{theme, words:[4]}]}]`; `theme`
  nascosto al client live.
- Ordine sequenziale (non casuale). `# ponytail: prevedibilità — un osservatore
  regolare può anticipare la partita successiva; per imprevedibilità,
  reintrodurre shuffle.` Interfaccia pubblica invariata ⇒ nessun caller da
  modificare.

## Collegamenti
- `model/GameData`: tipo prodotto da `next()`.
- `core/GameManager`: consuma `cyclicIterator()` (catch `UncheckedIOException`/
  `JsonIOException` in `makeNext`).
- `core/ServerMain`: costruisce il loader e ne stampa `total()`.
