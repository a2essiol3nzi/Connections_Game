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
| `groups` | `List<ThemeGroup>` | 4 gruppi {theme, `Set<words>`}; **immutabili** dopo il costruttore |
| `shuffledWords` | `List<String>` | 16 parole in ordine casuale (immutabile) |
| `finalized` | `AtomicBoolean` | `true` dopo la finalizzazione |
| `players` | `ConcurrentHashMap<Integer,PlayerState>` | chiave = **userId** |

Inner `ThemeGroup { theme, Set<String> words }` (runtime; distinto da `GameData.Group`).

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
