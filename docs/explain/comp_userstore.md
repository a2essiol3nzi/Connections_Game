# Documentazione componente: `persistence/UserStore` (gestione utenti)

## Responsabilità
Registrazione, login, aggiornamento credenziali, statistiche e persistenza
degli utenti. Unica fonte di verità sugli account lato server.

## ID univoco immutabile (consiglio prof.)
Ogni utente ha un `id` intero **immutabile**, assegnato alla registrazione.
- Mappa PRIMARIA `<id, User>`: la sua struttura NON cambia mai (la chiave id è
  fissa). Le modifiche di credenziali non la toccano.
- Indice secondario `<username, id>`: l'unico a cambiare in caso di rinomina.
Così le rinomine non alterano altre strutture che referenziano l'utente per id.

## Semplificazione password (deliberata)
Password salvate **IN CHIARO**. Scelta consapevole: il progetto è un esercizio
di reti/concorrenza, non di sicurezza. In produzione usare un hash. Non
copiare in contesti reali.

## Concorrenza (senza lock globale)
- `byId` è `ConcurrentHashMap` → operazioni di mappa thread-safe.
- ogni operazione su un account avviene sotto `synchronized(u)` sull'oggetto
  `User` → due utenti diversi NON si bloccano (il `ConcurrentHashMap` protegge
  solo le operazioni di mappa, NON i campi dentro l'oggetto `User`: serve il
  `synchronized(u)` per quello).
- `nextId` è `AtomicInteger` (assegnazione id thread-safe).
- la rinomina tocca solo `nameToId` → nessun lock strutturale necessario.
- Rispetta il vincolo: solo `synchronized`/`wait`/`notify`/`Atomic*`, NO locks.*.

## Persistenza
Utenti in RAM (pochi, accessi frequenti). Snapshot JSON periodico + atomico
(scrittura su `.tmp` poi `Files.move(ATOMIC_MOVE)`). Alternativa più efficiente
a larga scala = DB embedded (over-engineering qui).

## Nota su `persist()`
Itera `byId.values()` mentre `finalizeGame` aggiorna un `User` sotto
`synchronized(user)`: può risultare uno snapshot *leggermente mescolato* per
quel singolo utente (alcuni campi pre-, altri post-aggiornamento) nel JSON
salvato. I campi `int`/`String` sono atomici individualmente → nessuna
corruzione, solo possibile piccola staleness. Accettabile a questa scala.

## Campi di `User`
`id` (immutabile), `username`, `password` (chiara), `cumulativeScore` (classifica),
`puzzlesPlayed/Won/Lost/notFinished`, `currentStreak`, `maxStreak`,
`perfectPuzzles`, `mistakeHist[6]` (istogramma NYT: [0..3] vinti con 0..3 errori,
[4] persi, [5] non finiti in tempo).

## Collegamenti
- `core/Context`: istanzia `UserStore`.
- `core/GameManager.finalizeGame`: aggiorna le stats sotto `synchronized(user)`.
- `persistence/PersistenceThread`: chiama `persist()`.
