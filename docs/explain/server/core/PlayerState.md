# `core/PlayerState` — stato di UN giocatore nella partita attiva

## Ruolo
POJO che traccia l'avanzamento di un singolo **userId** dentro la partita
corrente. Istanziato da `ActiveGame.join` e interrogato da `GameManager`.

## Campi
| Campo | Tipo | Significato |
|-------|------|-------------|
| `userId` | int | **id immutabile** dell'account (non lo username) |
| `foundGroups` | `Set<Integer>` | indici (0..3) dei gruppi già individuati |
| `errorCount` | int | proposte ERRATE (0..4); 4 ⇒ sconfitta |
| `correctCount` | int | gruppi trovati (0..3); 3 ⇒ vittoria (4° implicito) |
| `finished` | boolean | `true` se ha vinto/perso (non può più proporre) |

## Metodi
- `score()` = `6*correctCount - 4*errorCount` (metrica §1).
- `alreadyFound(groupIndex)` — utility su `foundGroups`.

## Concorrenza
Oggetto NON thread-safe di per sé: il locking è **esterno e granulare** —
`ActiveGame.submit`/`outcomeOf`/`wordsOfFoundGroups`/`groupInfo` acquisiscono
`synchronized(ps)` solo per le modifiche a questo stato, consentendo N client di
sottomettere proposte PARALLELAMENTE senza serializzazione globale.

## Collegamenti
- `core/ActiveGame`: unico proprietario/modificatore (sotto `synchronized(ps)`).
- `core/GameManager`: legge `correctCount`/`errorCount` per stats e `finalizeGame`.
