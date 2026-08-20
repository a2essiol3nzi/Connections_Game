# `loader/GameLoader` — sorgente partite PIGRA (O(1) memoria, streaming)

## Ruolo
Carica partite da un file JSON di **grandi dimensioni** senza tenerle tutte in
RAM. Approccio **generatore/iteratore**: all'avvio conta gli oggetti con una
sola passata sequenziale (`JsonReader` + `skipValue`); `cyclicIterator()`
espone un iteratore *lazy* che legge **UN oggetto alla volta** via `JsonReader`
e, arrivato in fondo al file, riapre il file e ricomincia (ciclo infinito).
Memoria O(1) rispetto ai corpi delle partite: Gson fa data-binding di un solo
oggetto per volta; il file non viene mai letto tutto insieme.

**Robustezza a runtime**: un oggetto malformato (sintassi rotta o forma non
valida per `GameData`) NON ferma il server né impenna la CPU. Nell'iteratore,
dopo un errore di parse il `JsonReader` resta desincronizzato sul token rotto,
quindi si **riapre** il file e si saltano gli oggetti già emessi + quello
rotto, riprendendo dal successivo. `# ponytail: file interamente rotto ->
spin con reopen per oggetto; non capita con il file dei docenti (validato a
startup da countGames).`

## Stato interno
- `path` — percorso del file partite.
- `total` — numero partite (contate a costruzione).
- `GSON` — istanza `Gson` (condivisa).
- (dentro `cyclicIterator`) `index` — oggetti già emessi con successo, usato
  per riallineare il reader dopo un errore.

## Metodi principali
- `int total()` — numero partite disponibili.
- `Iterator<GameData> cyclicIterator()` — iteratore **lazy e ciclico
  all'infinito** (`hasNext()` ritorna **sempre `true`**): ogni `next()` parsia
  un solo oggetto; a fine array il reader viene riaperto e `index` azzerato
  (wrap ciclico). Su `JsonSyntaxException` riapre e salta `index + 1` oggetti
  (i buoni già emessi + quello rotto) riprendendo dal successivo — mai
  spin/loop infinito.
- `countGames()` (privato) — passata sequenziale: `beginArray()` + ciclo
  `skipValue()`; conta SOLO gli oggetti di primo livello (Gson bilancia lo skip
  anche se annidato). Nota: non gestisce entry malformate (un file corrotto a
  monte fa fallire la costruzione del loader; la tolleranza agli errori è solo
  a runtime nell'iteratore).
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
