# Analisi concorrenza e flussi — Server Connections

> Relazione tecnica. Analisi statica del codice `src/server/**` incrociata con i
> vincoli di progetto (`hermes_brain/`, §3: solo `synchronized`/`wait`/`notifyAll`/
> `atomic.*`, vietati `java.util.concurrent.locks.*`). Il modello grafico è in
> `docs/system_model.png`.

## 1. Modello del sistema (thread + stato condiviso)

### Thread attivi
| Thread | Molteplicità | Cosa fa |
|--------|--------------|---------|
| `ConnectionAcceptor` | 1 (main) | `accept()` loop, sottomette `ClientHandler` al pool |
| `ClientHandler` | N (pool `poolSize`=16) | 1 socket persistente/thread; dispatch 9 operazioni |
| `GameScheduler` | 1 | `sleep`→`finalizeGame`→`persist`→`persistHistory`→UDP→`rotate` |
| `PersistenceThread` | 1 | timer periodico (30s): `persist`+`persistHistory` |
| ShutdownHook | 1 | su SIGTERM/SIGINT: `persist`+`persistHistory` |

### Stato condiviso e sua protezione
- `GameManager.current` — `synchronized(this)` su tutti gli accessi (`current()`, `rotate`, `finalizeGame`).
- `GameManager.history` — `ConcurrentHashMap`; `persistHistory` è `synchronized(this)`.
- `GameManager.onlineUsers` — `ConcurrentHashMap.newKeySet()`.
- `ActiveGame.players` — `ConcurrentHashMap`; mutazioni sotto `synchronized(this=g)`.
- `ActiveGame.finalized` — `AtomicBoolean`.
- `UserStore.byId`/`nameToId` — `ConcurrentHashMap`; scritture strutturali sotto `synchronized(this)`; campi del singolo `User` sotto `synchronized(u)`.
- `GameLoader.CyclicGameIterator` — **nessuna sincronizzazione** (stato mutabile: `reader`, `open`, `index`).

I lock che coesistono formano questa gerarchia d'acquisizione (per l'analisi deadlock):
`GameManager(this)` → `ActiveGame(g)` → `UserStore(this)`/`User(u)`.

---

## 2. Problemi rilevati

Ordine: prima i race/bug di correttezza, poi robustezza/bottleneck. Severità:
🔴 corruzione dati o crash · 🟡 comportamento errato osservabile · 🟢 minore/robustezza.

### 🔴 #1 — Doppio login concorrente (TOCTOU su `isOnline` + `registerLogin`)
**Race reale: SÌ.**

`ClientHandler.dispatch`, caso `login` (righe 86-95):
```
Errors r = ctx.users.login(...);          // A: verifica credenziali
UserStore.User u = ctx.users.getByName(...);
if (ctx.games.isOnline(u.id))              // B: check "già online"
    return Response.err(ERR_ALREADY_LOGGED_IN);
loggedInUserId = u.id;
ctx.games.registerLogin(u.id);             // C: set online
```
`isOnline` (B) e `registerLogin` (C) sono due operazioni **separate** su `onlineUsers`, senza lock che le renda atomiche. Due `ClientHandler` (due socket) per lo stesso account eseguono B→B (entrambi vedono `false`) → C→C: **entrambi passano**. Risultato: due connessioni "loggate" sullo stesso `userId`, mentre `ERR_ALREADY_LOGGED_IN` doveva impedirlo.

**Ownership/flusso:** `onlineUsers` è condiviso tra tutti gli handler; il check-then-act su due handler diversi si interfoglia. `ConcurrentHashMap` protegge la singola put/contains, non la sequenza B→C.

**Impatto:** al logout/disconnessione del primo, `registerLogout` rimuove l'`userId` dal set: il secondo resta "loggato" ma non più in `onlineUsers` → auto-join alla rotazione salta, incongruenza di stato.

**Fix (dentro i vincoli):** usare l'atomicità di `newKeySet()`:
```java
// in GameManager: unica operazione atomica, ritorna true se ha aggiunto
public boolean tryLogin(int userId) {
    boolean added = onlineUsers.add(userId);   // add() torna false se già presente
    if (added) { ActiveGame g = current(); if (g != null) g.join(userId); }
    return added;
}
```
e nel handler: `if (!ctx.games.tryLogin(u.id)) return Response.err(ERR_ALREADY_LOGGED_IN);`. Rimuove il TOCTOU: `Set.add` è la check-and-set atomica.

---

### 🟡 #2 — `registerLogin`/`join` sulla partita che sta ruotando
**Race reale: SÌ, finestra stretta ma concreta.**

`GameManager.registerLogin` (130-135):
```java
onlineUsers.add(userId);
ActiveGame g = current();   // snapshot della partita corrente
if (g != null) g.join(userId);
```
Tra `current()` e `g.join()`, lo scheduler può eseguire `rotate()`: `current` diventa una **nuova** `ActiveGame`. Il login fa `join` sulla partita **vecchia** (`g`), che nel frattempo può essere finalizzata → `join` ritorna `ERR_FINISHED` (scartato, ritorno ignorato). L'utente è in `onlineUsers`, quindi **verrà auto-joinato alla prossima rotazione** — ma per il round immediatamente successivo alla sua login **non partecipa**, pur risultando online.

**Ownership/flusso:** `current` è di proprietà di `GameManager` sotto il suo lock; `registerLogin` lo legge sotto lock (via `current()`) ma poi **rilascia** il lock prima di `join`. La finestra è esattamente tra rilascio-lock e `join`.

**Perché non è 🔴:** nessuna corruzione; `finalized` in `join` impedisce mutazioni su partita chiusa. È una perdita di partecipazione per un round, silenziosa.

**Fix:** fare join dentro il lock del manager, oppure — più semplice e coerente col resto — accettare il ritardo di un round come da design (auto-join alla rotazione) e **documentarlo**. Se si vuole join immediato garantito: ripetere finché `current()` è stabile:
```java
public void registerLogin(int userId) {
    onlineUsers.add(userId);
    synchronized (this) {                 // stesso monitor di rotate()
        if (current != null) current.join(userId);
    }
}
```
`registerLogin` non era `synchronized`; renderlo tale lo serializza con `rotate`/`finalizeGame` e chiude la finestra. Costo: trascurabile (login è raro).

---

### 🟡 #3 — Notifica UDP inviata ai partecipanti giusti ma DOPO persist, PRIMA di rotate: OK; ma lista `participants()` catturata su `g` post-finalize
**Race reale: NO (falso allarme), ma c'è una fragilità da annotare.**

`GameScheduler.run` (40-55): `finalizeGame` sigilla `g.finalized=true`, poi `participants()` è chiamata su `g` (la partita appena finalizzata, non su `current`). Dopo `finalized=true`, `players` non può più crescere (`join` rifiuta). Quindi la lista UDP è **stabile e corretta**. Nessuna race.

**Fragilità (🟢):** `participants()` viene ricalcolata due volte (una in `finalizeGame`, una nello scheduler): due `HashSet` copiati. Non è un bug, è lavoro ridondante trascurabile. Nessuna azione necessaria.

---

### 🟡 #4 — `finalizeGame`: statistiche contate due volte se `rotate` non separa i round / doppia finalizzazione
**Race reale: possibile SÌ su interruzione, NO nel flusso lineare.**

`finalizeGame` non è idempotente rispetto a `UserStore`: incrementa `puzzlesPlayed`, `cumulativeScore`, ecc. È chiamato **solo** dallo scheduler (single-thread), una volta per round, prima di `rotate`. Nel flusso normale è corretto: `finalized` viene settato, ma **non impedisce una seconda `finalizeGame` sullo stesso `g`** (il guard sarebbe `finalized`, ma `finalizeGame` setta e prosegue sempre).

**Quando esplode:** se un domani si aggiunge un secondo trigger di `finalizeGame` (es. fine-partita anticipata perché tutti hanno finito), lo stesso round verrebbe contato due volte nelle stat cumulative. Oggi non accade (unico chiamante), quindi **latente**, non attivo.

**Fix difensivo (1 riga):** far uscire `finalizeGame` se già finalizzato:
```java
if (!g.finalized.compareAndSet(false, true)) return;  // sostituisce g.finalized.set(true)
```
`compareAndSet` rende `finalizeGame` idempotente: la seconda chiamata è no-op. Costo zero, chiude la classe di bug.

---

### 🟡 #5 — `finalizeGame`: `synchronized(g)` annidato dentro `synchronized(this)` — ordine lock
**Race reale: NO. Deadlock: NO (verificato). Annotazione di correttezza.**

`finalizeGame` è `synchronized(this=GameManager)`; dentro prende `synchronized(g=ActiveGame)` per leggere `PlayerState`, poi `synchronized(user=User)` per aggiornare le stat. Ordine: **GM → g → user**.

Controllo incrociato di tutti i path che prendono >1 lock:
- `finalizeGame`: GM → g → user. ✓
- `gameInfo(roundId=-1)`: GM(`current()`) rilasciato, poi g. Non annidato con user.
- `leaderboard`: nessun lock GM/g; solo `user` (uno per volta) + `store(this)` via `getByName`. Ordine store → user.
- `submit`/`join`: solo g.
- `updateCredentials`: store(this) → user.

**Verdetto:** nessun ciclo di lock. `finalizeGame` prende GM→g→user; nessun altro path prende gli stessi lock in ordine inverso (es. nessuno prende `user` e poi `g`, né `g` e poi `GM`). **Niente deadlock.** L'ordine è consistente. Nota di stile: il `synchronized(g)` interno è **ridondante** dato che `g.finalized` è già `true` e nessun submit può mutare `PlayerState` dopo il sigillo — ma tenerlo è difensivo e corretto, non serve rimuoverlo.

---

### 🟡 #6 — `leaderboard`: snapshot punteggi non atomico rispetto a `finalizeGame`
**Race reale: SÌ, ma tollerabile per la semantica di una classifica.**

`leaderboard` (277-315) legge `u.cumulativeScore` sotto `synchronized(u)`, per-utente, in un loop. `finalizeGame` scrive `user.cumulativeScore += score` sotto `synchronized(user)`. I due sono appaiati sul **singolo** utente → nessuna lettura strappata (no torn read del singolo int, e comunque `int` è atomico in JMM).

**Ma:** la classifica non è uno snapshot atomico dell'insieme. Se `finalizeGame` aggiorna U1 e U2 mentre `leaderboard` sta iterando, la classifica può includere il nuovo score di U1 e il vecchio di U2. Risultato: **ordinamento leggermente incoerente** in un istante di transizione.

**Verdetto:** accettabile. Una leaderboard "eventualmente consistente" è la norma; nessun requisito di §1 impone snapshot atomico globale, e ottenerlo richiederebbe un lock globale su tutti gli User (bottleneck). **Nessuna azione**, semmai una riga di commento. `ponytail:` snapshot per-utente, lock globale solo se il bando richiede classifica atomica.

---

### 🔴 #7 — `GameLoader.CyclicGameIterator` NON è thread-safe (ma è usato da 1 thread solo?)
**Race reale: NO oggi, 🔴 se cambia un'assunzione.**

`CyclicGameIterator` ha stato mutabile non protetto (`reader`, `open`, `index`) e `next()` fa I/O + mutazione. Chi chiama `games.next()`?
- `GameManager.makeNext()`, chiamato da: costruttore (single-thread), e `rotate()` (`synchronized(this)`).

`rotate` e `finalizeGame` sono entrambi `synchronized(this)` e chiamati **solo** dallo scheduler (1 thread). Quindi `next()` è **di fatto confinato a un thread**. **Nessuna race oggi.**

**Perché è 🔴 latente:** l'assunzione "solo lo scheduler ruota" non è imposta dal tipo. Se un handler chiamasse mai `makeNext`/`next` (es. futuro "forza rotazione"), lo stato dell'iteratore si corromperebbe (reader condiviso, `index` sballato) → `JsonSyntaxException` a catena o partite saltate. L'iteratore andrebbe documentato come *single-writer, confinato allo scheduler*.

**Nota bug di riallineamento (indipendente):** in `next()`, dopo `JsonSyntaxException`, il ricalcolo `for (i=0; i<=index; i++) reader.skipValue()` salta `index+1` oggetti. Se l'oggetto rotto è **l'ultimo** dell'array, dopo lo skip `reader.hasNext()` è `false` ma il ramo di wrap è nel ciclo `while(true)` successivo: gira, riapre, riparte. Corretto ma con un giro a vuoto. Se il file avesse **due** oggetti rotti consecutivi, `index` avanza di 1 per errore mentre gli oggetti saltati sono di più → possibile **disallineamento** e skip di partite valide. Con il file dei docenti (ben formato) non accade; è un rischio solo su input corrotto.

---

### 🟢 #8 — `gameInfo(roundId=-1)`: `remainingSec` letto fuori dal lock di `g`
**Race reale: benigna.**

Righe 172-175: `g.roundId`, `g.gameId`, `g.endTimeMs` letti **fuori** da `synchronized(g)`. Sono `final` (immutabili) → lettura sicura senza lock. `System.currentTimeMillis()` è indipendente. Poi `synchronized(g)` per `PlayerState`. **Corretto.** Nessuna azione.

---

### 🟢 #9 — `history` `trimHistory` sotto lock, ma `history.size()` check-then-trim
**Race reale: NO (single-writer).**

`finalizeGame` (`synchronized this`) fa `history.put` poi `if size>CAP trimHistory`. Unico writer è lo scheduler. `persistHistory` (`synchronized this`) legge `history` — serializzato col put. Lettori concorrenti (`gameInfo`/`gameStats` su storico) usano `history.get` su `ConcurrentHashMap`: safe. `trimHistory` rimuove il min mentre un lettore potrebbe leggerlo → `get` torna `null` → gestito (`ERR_GAME_NOT_FOUND`). **Corretto.**

---

### 🟢 #10 — Bottleneck: `UserStore.persist()` e `leaderboard` prendono `synchronized(u)` su OGNI utente
**Non è un bug, è scalabilità.**

`persist()` è `synchronized(this)` (serializza le 3 fonti) e dentro prende `synchronized(u)` per ogni User. Con molti utenti + persist frequente + submit concorrenti, i submit che aggiornano stat (`finalizeGame`) possono attendere. Ma: `persist` gira ogni 30s, `finalizeGame` una volta per round (600s). Contesa quasi nulla nel dominio reale (progetto didattico, pochi utenti). **Nessuna azione**; annotare come ceiling noto.

---

## 3. Riepilogo azioni consigliate

| # | Severità | Problema | Azione |
|---|----------|----------|--------|
| 1 | 🔴 | Doppio login (TOCTOU `isOnline`/`registerLogin`) | `onlineUsers.add()` atomico come check-and-set |
| 2 | 🟡 | `registerLogin.join` su partita che ruota | `synchronized(this)` attorno a `current.join` |
| 4 | 🟡 | `finalizeGame` non idempotente | `finalized.compareAndSet(false,true)` come guard |
| 7 | 🔴 lat. | Iteratore loader non thread-safe | documentare confinamento allo scheduler; robustezza riallineamento su input corrotto |
| 3,5,6,8,9,10 | 🟢 | Falsi allarmi / ceiling noti | commento, nessuna modifica |

**Bug logici NON di concorrenza intercettati durante l'analisi:**
- #7 riallineamento loader su ≥2 oggetti rotti consecutivi (solo input corrotto).

Nessuna delle azioni introduce `java.util.concurrent.locks.*`: tutte usano `synchronized`,
`AtomicBoolean.compareAndSet`, o l'atomicità di `ConcurrentHashMap.newKeySet().add()` —
dentro i vincoli §3.
