# `core/ActiveGame` — partita ATTIVA (globale, unica)

## Ruolo
Rappresenta l'unica partita in corso. Valuta le proposte dei giocatori e tiene
lo stato per-giocatore. Le parole sono inviate **mescolate** (`shuffledWords`);
i `theme` restano lato server.

## Campi
| Campo | Tipo | Note |
|-------|------|------|
| `gameId` | int | id sorgente (si ripete al wrap del loader ciclico) |
| `roundId` | int | id **UNIVOCO** di questa esecuzione (monotono) |
| `startTimeMs` / `endTimeMs` | long | finestra di gioco |
| `groups` | `List<ThemeGroup>` | 4 gruppi {theme, `Set<words>`, `Set<wordsLower>`}; **immutabili** dopo il costruttore |
| `shuffledWords` | `List<String>` | 16 parole in ordine casuale (immutabile) |
| `boardLower` | `Set<String>` | tutte le parole in **minuscolo** (validazione case-insensitive) |
| `finalized` | `AtomicBoolean` | `true` dopo la finalizzazione |
| `players` | `ConcurrentHashMap<Integer,PlayerState>` | chiave = **userId** |

Inner `ThemeGroup { theme, Set<String> words, Set<String> wordsLower }` (runtime;
distinto da `GameData.Group`). `wordsLower` = variante minuscola di `words` per
il **confronto case-insensitive** (`ciao == CIAO`); le parole ORIGINALI restano in
`words`, usate per l'invio al client e per lo storico. `boardLower` = minuscole
di tutta la board (`lowerOf(new HashSet<>(all))`). Helper `lowerOf(Collection)` →
`Set` di `toLowerCase(Locale.ROOT)`.

## Validazione proposta (case-insensitive)
Le proposte e i confronti avvengono sulle **maiuscole lower** (`wordsLower`/
`boardLower`): una proposta con maiuscole diverse dalle parole sorgenti viene
riconosciuta come partita dal gioco. [OK_FOUND vs OK_WRONG](../../../hermes_brain/gotchas.md)

## Enum esito
- `JoinResult { OK, ERR_FINISHED, ERR_NO_ACTIVE_GAME }` — ogni costante porta
  `Errors error` (null sui successi); `isOk()`, `error()`.
- `SubmitResult { OK_FOUND("CORRECT"), OK_WRONG("WRONG"), ERR_MALFORMED,
  ERR_FINISHED, ERR_NOTJOINED, ERR_NO_ACTIVE_GAME }` — `resultLabel()` sui
  successi, `error()` sugli errori.
- `Outcome { WON, LOST, NOT_FINISHED }`.

## Metodi principali
- `join(userId)` — **`synchronized(this)`** (raro: solo al login): se `finalized`
  ⇒ `ERR_FINISHED`; altrimenti `computeIfAbsent` PlayerState ⇒ `OK`.
- `submit(userId, words)` — **lock GRANULARE su `PlayerState`**
  (`synchronized(ps)`), NON su `ActiveGame`: N client sottomettono in parallelo.
  Validazioni read-only (null/4/duplicati/parola-fuori-gioco) **senza lock**;
  ri-check di `finalized` dentro il lock (anti-TOCTOU). Esiti: `OK_FOUND`
  (+1 corretto, a 3 ⇒ finished), `OK_WRONG` (+1 errore, a 4 ⇒ finished),
  `ERR_MALFORMED` (nessun impatto), `ERR_FINISHED`/`ERR_NOTJOINED`.
  **Det. parola-già-usata**: usato `!Collections.disjoint(proposed,
  group.words)` (intersezione non vuota) invece di `containsAll` — così basta
  UNA parola già utilizzata in un gruppo per marcare la proposta malformata.
- `groupInfo()` → `GameData.Group[]` (tema+parole) per lo storico.
- `wordsOfFoundGroups(ps)` — `synchronized(ps)` (groups immutabili).
- `getState`, `participants()`, `outcomeOf(ps)` (`synchronized(ps)`).

## Concorrenza
`finalized` `AtomicBoolean` per visibilità cross-thread. `join` sincronizzato su
`this` (poco frequente); `submit` a granularità fine su `PlayerState` per
consentire valutazioni parallele. Validazioni read-only su campi immutabili
(groups/shuffledWords) senza lock. `# ponytail: granular lock su ps; se servisse
lock globale su tutta la partita, synchronized(this) su submit (ma serializza le
proposte).`

## Collegamenti
- `core/PlayerState`: stato per giocatore (lock granulare).
- `core/GameManager`: crea/ruota, delega `submit`, legge `groupInfo()`/`finalized`.
- `network/GameScheduler`: usa `endTimeMs`, `finalized`, `roundId`, `participants()`.
