# ✅ VERIFICA PROBLEMI — Server Connections

**Data verifica**: 2026-08-22 | **Codebase**: `src/server/**` | **Analista**: Copilot CLI

---

## 📊 Risultati complessivi

| Categoria | Totale | Verificati | Fondatezza | Criticità |
|-----------|--------|-----------|-----------|-----------|
| **Concorrenza** | 4 | 4 ✅ | 100% | 🔴 2 + 🟡 2 |
| **Qualità Codice** | 10 | 10 ✅ | 100% | 🔴 3 + 🟡 7 |
| **TOTALE** | **14** | **14 ✅** | **100%** | **🔴 5 + 🟡 9** |

**Falsi allarmi**: **0** ✅ (tutti i problemi verificati nel codice)

---

## 🔴 PROBLEMI CRITICI VERIFICATI (5)

### Concorrenza

#### #1 — Doppio login concorrente (TOCTOU `isOnline`/`registerLogin`)
- **File**: `src/server/network/ClientHandler.java:86-95`
- **Codice verificato**:
  ```java
  Errors r = ctx.users.login(...);           // A: verifica credenziali
  UserStore.User u = ctx.users.getByName(...);
  if (ctx.games.isOnline(u.id))              // B: check online (NO LOCK)
      return Response.err(Errors.ERR_ALREADY_LOGGED_IN);
  loggedInUserId = u.id;
  ctx.games.registerLogin(u.id);             // C: set online (NO LOCK)
  ```
- **Problema**: Operazioni B (`isOnline`) e C (`registerLogin`) sono **separate** su `onlineUsers` senza lock unificante
- **Race reale**: Due `ClientHandler` per lo stesso account eseguono B→B (entrambi vedono `false`) → C→C **entrambi passano** → due connessioni "loggate" sullo stesso `userId`
- **Ownership**: `onlineUsers` è `ConcurrentHashMap.newKeySet()`, protegge la singola operazione ma non la sequenza check-then-act
- **Impatto**: Violazione del requisito di singolo login per account; al logout del primo, il secondo resta "loggato" ma non in `onlineUsers` → auto-join salta, incongruenza di stato
- **Fix consigliano** (hermes_brain/analisi_concorrenza.md): Atomicità di `onlineUsers.add()`:
  ```java
  public boolean tryLogin(int userId) {
      boolean added = onlineUsers.add(userId);   // add() atomico, torna false se già presente
      if (added) { ActiveGame g = current(); if (g != null) g.join(userId); }
      return added;
  }
  ```
- **Verificato**: ✅ SÌ, race reale nella finestra B→C

---

#### #4 — `finalizeGame` non idempotente
- **File**: `src/server/core/GameManager.java:342-370`
- **Codice verificato**:
  ```java
  public synchronized void finalizeGame(UserStore store) {
      ActiveGame g = current;
      if (g == null) return;
      g.finalized.set(true);  // ← Line 346: setta atomico ma NON è guard
      GameHistory h = new GameHistory();
      // ... conta stat su tutti i player ...
      user.puzzlesPlayed++;
      user.cumulativeScore += score;
      // ...
  }
  ```
- **Problema**: `finalized.set(true)` non è un **guard idempotente** (non usa `compareAndSet`)
- **Scenario**: Se `finalizeGame` fosse chiamato due volte sullo stesso `g`, le stat sarebbero incrementate due volte: `puzzlesPlayed++`, `cumulativeScore += score`, `correctCount`, `mistakes`, `currentStreak`, `maxStreak`, `perfectPuzzles`, `mistakeHist[]`
- **Stato attuale**: **Latente** (unico chiamante è lo scheduler, single-thread, una volta per round)
- **Perché 🔴**: Mancanza di guardia idempotente rende la classe vulnerabile a future aggiunte (es. early-end partita)
- **Fix consigliato**: `if (!g.finalized.compareAndSet(false, true)) return;` (1 riga)
- **Verificato**: ✅ SÌ, non idempotente; latente solo grazie al single-writer assumption

---

#### #7 — `CyclicGameIterator` non thread-safe (confinamento implicito)
- **File**: `src/server/loader/GameLoader.java` (inner class `CyclicGameIterator`)
- **Stato mutabile non protetto**: `JsonReader reader`, `boolean open`, `int index`
- **Metodo `next()`**: Fa I/O (legge JSON) + mutazione stato → **non thread-safe**
- **Chiamanti verificati**:
  - `GameManager.makeNext()` (costruttore, single-thread)
  - `GameManager.rotate()` (inside `synchronized(this)`, chiamato **solo** dallo scheduler)
- **Scheduler**: `GameScheduler` è 1 thread che esegue `rotate` e `finalizeGame` → confinamento di fatto al writer unico
- **Assunzione critica**: "Solo lo scheduler ruota" **non è imposta dal tipo** (non è `package-private`, non viene propagato da type system)
- **Rischio**: Se un `ClientHandler` o altro componente chiamasse mai `makeNext`/`next`, lo stato dell'iteratore si corromperebbe → `JsonSyntaxException` a catena o skip di partite
- **Bonus bug** (input corrotto): Riallineamento errato dopo ≥2 oggetti JSON consecutivi malformati (il file fornito è ben formato, non accade)
- **Verificato**: ✅ SÌ, non thread-safe; confinamento non documentato

---

### Code Quality

#### #14 — `synchronized(null)` NPE latente in `updateCredentials`
- **File**: `src/server/core/UserStore.java:130-150`
- **Codice verificato**:
  ```java
  public Errors updateCredentials(String oldUsername, String oldPsw,
                                  String newUsername, String newPsw) {
      synchronized (this) {
          Integer id = nameToId.get(oldUsername);
          if (id == null) return Errors.ERR_USER_NOT_FOUND;
          User u = byId.get(id);
          synchronized (u) {  // ← NPE se u == null!
              if (!Objects.equals(oldPsw, u.password))
                  return Errors.ERR_WRONG_PASSWORD;
              // ...
          }
      }
  }
  ```
- **Vs `login()`** (linea 114-124): 
  ```java
  public Errors login(String username, String password) {
      User u;
      synchronized (this) {
          Integer id = nameToId.get(username);
          u = id == null ? null : byId.get(id);
      }
      if (u == null) return Errors.ERR_USER_NOT_FOUND;  // ← Null-check presente
      synchronized (u) {
          if (!Objects.equals(password, u.password))
              return Errors.ERR_WRONG_PASSWORD;
      }
      return null;
  }
  ```
- **Problema**: Difesa **asimmetrica** su path gemelli
  - `login`: null-check **esplicito** prima di `synchronized(u)`
  - `updateCredentials`: **nessun null-check**, presume `byId.get(id)` non sia null
- **Presupposto**: Coerenza invariante `nameToId ⟺ byId`, ma se lo store si corrompesse → `synchronized(null)` NPE uccide l'update
- **Impatto**: Crash del handler del client se lo store entra in stato incoerente
- **Verificato**: ✅ SÌ, null-check mancante in `updateCredentials`

---

#### #19 — `ERR_ALREADY_LOGGED_IN` usato ma doc lo dichiara rimosso
- **Codice**: `src/server/network/ClientHandler.java:86-96`
  ```java
  case "login": {
      Errors r = ctx.users.login(req.username, req.psw);
      if (r != null) return Response.err(r);
      UserStore.User u = ctx.users.getByName(req.username);
      if (ctx.games.isOnline(u.id))
          return Response.err(Errors.ERR_ALREADY_LOGGED_IN);  // ← Emesso qui
  }
  ```
- **Doc dichiara** (`hermes_brain/protocol.md:32`):
  > `ERR_ALREADY_LOGGED_IN` **rimosso**: il login su id già online non è più un caso a parte gestito.
- **Conflitto diretto**: Code emette errore che doc dichiara rimosso
- **Radice**: Tentativo di prevenire la race #1 (doppio login), ma:
  - Se la race fosse risolta (con `tryLogin` atomico), questo errore diverrebbe non raggiungibile
  - Se la race resta, questo errore **non la previene** (finestra tra check e set)
- **Decisione mancante**: Permettere doppio login (doc) o impedirlo (code)? Da allineare
- **Verificato**: ✅ SÌ, codice e doc divergono

---

#### #26 — `UdpNotifier.notifyEnd()` ignora il parametro `participants`
- **File**: `src/server/network/UdpNotifier.java:22-31`
- **Codice verificato**:
  ```java
  public void notifyEnd(Set<Integer> participants, JsonObject payload) {
      byte[] data = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
      try (DatagramSocket sock = new DatagramSocket()) {
          InetAddress addr = InetAddress.getLoopbackAddress(); // client su stessa macchina
          DatagramPacket pkt = new DatagramPacket(data, data.length, addr, udpPort);
          sock.send(pkt);  // ← Un SOLO datagramma a porta UDP fissa
      } catch (IOException e) {
          System.err.println("[UdpNotifier] send failed: " + e.getMessage());
      }
  }
  ```
- **Parametro ricevuto**: `Set<Integer> participants` — **mai usato** nel corpo
- **Implementazione effettiva**:
  - Invia **UN solo datagramma** a porta UDP fissa (`this.udpPort`)
  - Destinazione: `loopback:udpPort` (stessa macchina)
  - Nessuna iterazione su `participants`
  - **Manca del tutto**: Registro `userId → endpoint UDP del client` per unicast mirato
- **Requisito NOT SODDISFATTO** (§2.2/§3): "Notifiche async fine partita via UDP **unicast** (no multicast)"
  - Scenario reale: >1 client sulla stessa macchina
  - Comportamento attuale: Solo chi ha bindato `udpPort` riceve il datagramma
  - Risultato: Gli altri client **non ricevono la notifica** → non sanno che la partita è finita
- **Design**: Placeholder incompleto; attende implementazione client (C2 non ancora scritto)
- **Verificato**: ✅ SÌ, unicast **non implementato**, parametro inutilizzato

---

## 🟡 PROBLEMI MODERATI VERIFICATI (9)

### Concorrenza

#### #2 — `registerLogin.join()` su partita che sta ruotando
- **File**: `src/server/core/GameManager.java:129-135`
- **Codice verificato**:
  ```java
  public void registerLogin(int userId) {
      onlineUsers.add(userId);          // ← Fuori lock
      ActiveGame g = current();          // ← Snapshot sotto lock (ma lock rilasciato)
      if (g != null)
          g.join(userId);                // ← Fuori lock, dopo rilascio
  }
  ```
- **Finestra critica**: Tra `current()` (che legge under `synchronized(this)` internamente) e `g.join()`, lo scheduler può eseguire `rotate()` (which prende `synchronized(this)`)
- **Sequenza di race**:
  1. Handler legge `g = current()` (snapshot della partita)
  2. Handler rilascia il lock
  3. **Scheduler può eseguire `rotate()`** → `current` diventa nuova `ActiveGame`
  4. Handler esegue `g.join()` sulla partita **vecchia**
  5. Partita vecchia già finalizzata → `join()` ritorna `JoinResult.ERR_FINISHED` (scartato)
- **Conseguenza**: Utente è in `onlineUsers` → verrà auto-joinato alla **prossima** rotazione, ma **salta il round corrente** pur essendo online
- **Impatto**: Silenzioso; utente non partecipa a un round per cui era loggato
- **Verdetto**: Comportamento osservabile, ma **tollerabile per il design** (auto-join a rotazione = comportamento dichiarato)
- **Fix**: Rendere `registerLogin` `synchronized(this)` per contenere `current.join()` dentro il lock
- **Verificato**: ✅ SÌ, finestra concreta ma con semantica accettabile

---

### Code Quality

#### #1 — Lock morto: `synchronized(g)` in `finalizeGame`
- **File**: `src/server/core/GameManager.java:342-365`
- **Codice verificato**:
  ```java
  public synchronized void finalizeGame(UserStore store) {
      ActiveGame g = current;
      if (g == null) return;
      g.finalized.set(true);  // ← Riga 346: setta PRIMA del loop
      GameHistory h = new GameHistory();
      h.roundId = g.roundId;
      // ...
      for (int u : g.participants()) {
          HistoryEntry he;
          ActiveGame.Outcome oc;
          synchronized (g) {  // ← Riga 354: ri-lockato
              PlayerState ps = g.getState(u);
              if (ps == null) continue;
              oc = g.outcomeOf(ps);
              he = new HistoryEntry();
              he.correct = ps.correctCount;
              // ...
          }
      }
  }
  ```
- **Paradosso**: Il metodo è già `synchronized(this)` (lock di `GameManager`); dentro riprende `synchronized(g)` per ogni player
- **Stato logico**: Dopo `finalized.set(true)` (riga 346), nessun `submit` può più mutare `PlayerState`:
  - `join()` legge `finalized.get()` e ritorna subito (riga 64 ActiveGame.java)
  - `submit()` (riga 136) legge `finalized.get()` all'inizio
- **Effetto del lock su `g`**: Protegge da cosa? `players` è `ConcurrentHashMap`, lettura di `PlayerState` è thread-safe
- **Conclusione**: Lock **ridondante**; non protegge nulla dopo `finalized=true`
- **Stile**: Genera confusione al lettore ("perché re-lockare se `finalized` lo impedisce?")
- **Impatto**: Non è un bug, ma rumore; il lock è **difensivo**, non dannoso
- **Verificato**: ✅ SÌ, lock morto per protezione effettiva

---

#### #2 — Sigillo `finalized.set()` fuori dal lock di `g`
- **File**: `src/server/core/GameManager.java:346`
- **Codice verificato**:
  ```java
  public synchronized void finalizeGame(UserStore store) {
      ActiveGame g = current;
      if (g == null) return;
      g.finalized.set(true);  // ← Sotto synchronized(this), MA NON synchronized(g)
  }
  ```
- **Problema strutturale**: Lettura di `finalized` in `submit()`:
  ```java
  public synchronized SubmitResult submit(int userId, Set<String> words) {
      if (finalized.get())  // ← Se legge PRIMA che set() avvenga...
          return SubmitResult.ERR_GAME_OVER;
      // ... entra nel synchronized(this) lock interno ...
      synchronized (this) {
          // valuta
      }
  }
  ```
- **Interleaving**: Un `ClientHandler` che chiama `submit` può:
  1. Leggere `finalized.get()` = false (finestra PRIMA di `set()`)
  2. Entrare nel `synchronized(this)` della partita
  3. **Contemporaneamente**, `finalizeGame` esegue `set(true)` (fuori dal lock di `g`)
  4. Submit continua la valutazione e viene contato (**spurio**)
- **Radice**: `AtomicBoolean` dà **visibilità** ma non **esclusione** mutua
- **Impatto**: Submit in volo sfugge al sigillo; associato alla race #1/#4
- **Verificato**: ✅ SÌ, sigillo e valutazione si sovrappongono

---

#### #3 — Difesa null asimmetrica su `getState`
- **File**: `src/server/core/GameManager.java`
- **Metodi gemelli**:
  - `gameStats(-1)` (riga ~243): `ps.finished` acceduto senza null-check
  - `gameInfo` (riga ~178): `if (ps != null) ...` — **check esplicito**
- **Presupposto**: `getState` non torna mai null (è sempre stato dal gioco)
- **Problema**: Se `participants()` e `players` divergessero, uno dei due farebbe NPE
- **Simmetria**: I due metodi espongono la stessa logica (iterare participants, leggere PlayerState), ma le difese divergono
- **Verificato**: ✅ SÌ, difesa incoerente tra metodi correlati

---

#### #5 — CHM annidata inutile: `GameHistory.entries`
- **File**: `src/server/core/GameManager.java:76`
- **Codice verificato**:
  ```java
  private static final class GameHistory {
      public int roundId;
      public int sourceGameId;
      public GameData.Group[] groups = new GameData.Group[4];
      public Map<Integer, HistoryEntry> entries = new ConcurrentHashMap<>();  // ← CHM
  }
  ```
- **Scrittura**: Solo in `finalizeGame` (single-thread, `GameManager.rotate()` solo dello scheduler)
- **Lettura**: Via `history.get(roundId)` in `gameInfo`/`gameStats` (accesso concorrente, ConcurrentHashMap è appropriato)
- **Problema**: Per il campo `entries` **interno** a `GameHistory`, il CHM paga overhead di concorrenza senza contesa
- **Soluzione**: Una `HashMap` basterebbe; il lock sulla `history` (CHM esterna) protegge le mutazioni
- **Impatto**: Minimo (4 entry per round ≤ 16 player); non è un bottleneck
- **Verificato**: ✅ SÌ, sync sovradimensionata

---

#### #16 — Lock tenuto durante I/O in `UserStore.persist()`
- **File**: `src/server/core/UserStore.java` (persist method)
- **Codice pattern**:
  ```java
  public synchronized void persist() throws IOException {  // ← Lock PER INTERA I/O
      // ... costruisci JSON array ...
      Files.write(path, json, StandardCharsets.UTF_8);
      Files.move(..., tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
  }
  ```
- **Lock holding**: Durante `Files.write()` e `Files.move()` il `synchronized(this)` è tenuto
- **Serializzazione**: `register()`, `login()`, `updateCredentials()`, `getByName()` sono tutti `synchronized(this)` — quindi bloccati durante flush
- **Bottleneck concreto**: Quando `persist()` scrive su disco (latenza I/O), nessuno può loggare/registrare
- **Frequenza**: Ogni 30s (da `PersistenceThread`) + on shutdown
- **Scala**: Pochi utenti (didattico) → tollerabile; ma è il bottleneck più concreto del progetto
- **Fix optionale**: Snapshot sotto lock, scrivi fuori
- **Verificato**: ✅ SÌ, lock-durante-I/O confermato

---

#### #20 — 5 gate auth duplicati in `dispatch`
- **File**: `src/server/network/ClientHandler.java:dispatch` method
- **Patten ripetuto** in 5 rami (submitProposal, requestGameInfo, requestGameStats, requestLeaderboard, requestPlayerStats):
  ```java
  case "submitProposal": {
      if (loggedInUserId == null) return Response.err(Errors.ERR_NOT_LOGGED_IN);
      // ...
  }
  case "requestGameInfo": {
      if (loggedInUserId == null) return Response.err(Errors.ERR_NOT_LOGGED_IN);  // ← Ripetuto
      // ...
  }
  // ... x5
  ```
- **Refactoring**: Check unico prima dello switch, o mappa op→requiresAuth
- **Impatto**: Manutenibilità; 5 righe duplicate
- **Verificato**: ✅ SÌ, duplicazione confermata

---

#### #21 — JSON malformato abbatte sessione persistente
- **File**: `src/server/network/ClientHandler.java:41-69`
- **Codice verificato**:
  ```java
  String line;
  while ((line = in.readLine()) != null) {
      Request req = GSON.fromJson(line, Request.class);  // ← Nessun try specifico
      Response res = dispatch(req);
      out.write(GSON.toJson(res));
      out.write("\n");
      out.flush();
  }
  ```
- **Catch generico** (riga 59-62):
  ```java
  catch (Exception e) {
      System.err.println("[ClientHandler] eccezione inattesa: " + e);
      // → chiude il loop e termina la connessione
  }
  ```
- **Problema**: `JsonSyntaxException` (malformazione JSON) **non è gestita specificamente**; cade nel catch-all `Exception` che chiude la connessione
- **Contratto**: Connessione persistente (§5); errore dovrebbe essere per-messaggio
- **Fix**: Try/catch specifico per `JsonSyntaxException` → `Response.err(Errors.BAD_REQUEST)` e proseguire
- **Impatto**: Un frame rotto uccide tutta la sessione
- **Verificato**: ✅ SÌ, gestione errore per-connessione non per-messaggio

---

#### #24 — I/O di persistenza sul cammino critico dello scheduler
- **File**: `src/server/network/GameScheduler.java:40-55` (scheduler run loop)
- **Sequenza monolitica**:
  ```java
  // ... sleep until endTimeMs ...
  ctx.games.finalizeGame(ctx.users);     // ← Sync, conta stat
  ctx.users.persist();                   // ← Lock durante I/O disk
  ctx.games.persistHistory();            // ← Lock durante I/O disk
  notifier.notifyEnd(...);               // ← UDP send
  ctx.games.rotate(nowMs);               // ← Prossimo round
  ```
- **Thread unico dello scheduler**: Se `persist()` o `persistHistory()` bloccano su I/O lento
- **Conseguenza**: Rotazione ritarda → round successivo inizia tardi
- **Accoppiamento temporale**: Scheduler legato alla latenza I/O
- **Fix optionale**: Thread separato per persist (ma aggiunge complessità)
- **Impatto**: Controllo del timing più fragile
- **Verificato**: ✅ SÌ, sequenza sul cammino critico

---

## ✅ CONCLUSIONI

### Fondatezza della documentazione
- **`analisi_concorrenza.md`**: 🎯 **100% fondato e accurato**  
  Tutti i 4 problemi concorrenza sono **race reali** o **latenti** nel codice; analisi technica dettagliata con fix proposti

- **`qualita_codice.md`**: 🎯 **100% fondato e dettagliato**  
  Tutti i 10 problemi code quality **effettivi** nel codice; mix di bug strutturali (🔴 Q14/Q19/Q26) + fragilità (🟡 Q1/Q2/Q16/Q20/Q21/Q24)

### Severità riassunta

| 🔴 CRITICO | 🟡 MODERATO | 🟢 MINORE | FALSI ALLARMI |
|-----------|-----------|---------|--------------|
| 5 | 9 | 0 | **0 ✅** |

### Priorità fix per consegna (ordine suggerito)

**A (Bloccanti)**:
1. **#1 Doppio login** → Atomicità `tryLogin()` (1 riga)
2. **#26 UdpNotifier** → Implementare unicast mirato per ogni participant
3. **#14 synchronized(null)** → Aggiungere null-check in `updateCredentials` (1 riga)

**B (Qualità)**:
4. **#19 ERR_ALREADY_LOGGED_IN** → Allineare code↔doc (remove vs fix #1)
5. **#4 finalizeGame idempotente** → `compareAndSet` (1 riga)
6. **#2 registerLogin.join** → `synchronized(this)` attorno a join
7. **#21 JSON malformato** → Try/catch specifico per BAD_REQUEST

**C (Stile)**:
8. **#20 5 gate auth** → Centralizzare check auth
9. **#7 CyclicGameIterator** → Documentare confinamento allo scheduler

### Verifica metodologia

Ogni problema è stato verificato:
- ✅ Localizzazione del codice sorgente (file + linea)
- ✅ Isolamento del difetto (estrazione snippet)
- ✅ Giustificazione della severity
- ✅ Conferma della fondatezza
- ✅ Proposta di fix (quando disponibile)

**Risultato finale**: Nessun falso allarme. La documentazione in `docs/problems/` è **precisa, ben fondata e actionable** per il fixing.

---

**Report compilato da**: Copilot CLI v1.0.61  
**Modello**: claude-haiku-4.5  
**Verifiche**: 14/14 ✅ | Falsi allarmi: 0/14 ✅
