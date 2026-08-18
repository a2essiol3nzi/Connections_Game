# Documentazione componente: `core/ActiveGame` + `core/PlayerState`

## `core/PlayerState` (stato di un giocatore nella partita attiva)
| Campo | Significato |
|-------|-------------|
| `username` | giocatore |
| `foundGroups` | `Set<Integer>` indici (0..3) dei gruppi già individuati |
| `errorCount` | proposte ERRATE (0..4); 4 ⇒ sconfitta |
| `correctCount` | gruppi trovati (0..3); 3 ⇒ vittoria (4° implicito) |
| `finished` | `true` se ha vinto/perso (non può più inviare proposte) |

`score()` = `6*correctCount - 4*errorCount`.

## `core/ActiveGame` (partita attiva globale)
- Costruita da un `GameData` (tema nascosto), le 16 parole sono MESCOLANO
  (`shuffledWords`, invariate al client; `theme` mai inviato).
- `join(username)`: registra il giocatore.
- `submit(username, words)`: valuta la proposta:
  - `OK_FOUND` gruppo corretto (nuovo) → +1 corretto;
  - `OK_WRONG` parole valide ma gruppo errato → +1 errore;
  - `ERR_MALFORMED` parole non valide / già usate / non 4 distinte → nessun impatto;
  - `ERR_FINISHED` / `ERR_NOTJOINED` → nessun impatto.
  Ordine: validità formale PRIMA della correttezza.
- `wordsOfFoundGroups(ps)`: parole dei gruppi già trovati (calcolo "remaining").
- `outcomeOf(ps)`: `WON` / `LOST` / `NOT_FINISHED`.
- `endTimeMs`: istante di scadenza (per lo scheduler).

## Concorrenza
Metodi `synchronized` (vincolo NO locks.*).

## Collegamenti
- `core/GameManager`: crea/ruota le `ActiveGame`, delega `submit`.
- `network/GameScheduler`: usa `endTimeMs` per la scadenza.
