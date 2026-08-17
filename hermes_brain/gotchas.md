# Gotchas & dove si concentra il lavoro

Insidie e punti che determineranno la maggior parte dello sforzo. Leggere prima
di implementare.

## 1. Client NIO + UDP concorrente (la parte più spinosa lato client)
Il client deve: (a) usare **NIO** (`Selector` + `SocketChannel` non-blocking)
sulla TCP persistente; (b) ricevere **notifiche UDP async** su thread a parte.
Due percorsi di ricezione concorrenti che aggiornano lo stato locale e
stampano a schermo → attenzione a race/InterruptedIOException e a non
bloccare la CLI. Pattern suggerito: thread NIO (send+recv TCP) + thread UDP
receiver + thread main per la CLI; stato locale protetto da lock semplice.

## 2. Modello di concorrenza server (la parte più spinosa lato server)
- Un **solo** `Game` attivo globale condiviso da tutti gli handler thread.
- Ogni connessione client gestita da un thread del **pool** (connessione
  persistente, non one-shot).
- **Scheduler thread** gestisce ciclo di vita partita: start → attesa timeout →
  end + notifiche UDP → start successiva. Deve sincronizzare la sostituzione
  dell'`activeGame` (es. `AtomicReference` o lock) con gli handler.
- **Persistenza periodica** su thread dedicato: scrittura JSON atomica
  (temp + rename) per evitare corruption al riavvio.
- Sync primitiva: `ConcurrentHashMap` per utenti; **lock globale sul Game**
  per le mutazioni di stato (proposte). # ponytail: lock globale sul game;
  per-account lock solo se servono throughput reali.

## 3. Distinzione malformata / errata (§2.2)
Facile da sbagliare: una proposta con parole già trovate o fuori gioco è
**malformata** → nessun impatto su stato/punteggio, solo messaggio di errore.
Una proposta di 4 parole valide ma non gruppo → **errata** → -4 + errore.
Controllare l'ordine: prima validità (4 distinte, tutte nel gioco, nessuna già
assegnata) → poi correttezza gruppo.

## 4. Join/auto-join e transizioni di stato giocatore
- Login durante partita attiva → join automatico con stato aggiornato
  (se era già loggato e ha fatto logout nella stessa partita, ripristina
  errori/gruppi trovati).
- Giocatore che **ha finito** (win/loss) nella partita attiva → non può inviare
  proposte finché non parte la successiva.
- Giocatore **loggato quando inizia nuova partita** → auto-partecipa, riceve
  nuove parole.
- Timeout globale chiude per TUTTI; chi non aveva win/loss è "not_finished".

## 5. Statistiche personali (§2.1) — interpretazione
Outcome per partita ∈ {WON, LOST(4 errori), NOT_FINISHED(timeout)}.
- Puzzles Completed = totale partite giocate.
- Win Rate = WON / giocate. Loss Rate = LOST / giocate.
- Current/Max Streak = serie di WON consecutive.
- Perfect = WON con 0 errori.
- Mistake Histogram: bin vittorie con 0-3 errori + fallite(4) + not_finished.
  (Testo spec dice "0 to 4 mistakes" ma 4 errori = loss, quindi bin separati.)

## 6. Metrica classifica NON definita
§2.1 non dice come ordinare la leaderboard. Scegliere (es. punteggio cumulativo
su tutte le partite, o win rate) e documentarlo nel PDF. Impatta S9.

## 7. File JSON partite MANCANTE
Nel repo non c'è il file delle 911 partite citato in §2.2. **Blocco runtime**.
Recuperare dal docente; nel frattempo generare dataset di test con lo schema in
`protocol.md`. Gestire comunque il caricamento "come se grande" (streaming o
caricamento lazy se serve; per 911 è trascurabile). # ponytail: load in
memoria ok per 911; se file enorme, memory-map/streaming.

## 8. Persistenza e riavvio
Utenti + storico partite su JSON, coerenti, riusabili al restart. Decidere se
persistere anche la partita **in corso** (semplificabile: no, si ricrea).
Scrittura atomica obbligatoria.

## 9. Password = boundary di sicurezza
Mai in chiaro: hash + salt (es. PBKDF2/HmacSHA256 o bcrypt). Validazione input
su tutti i campi ricevuti (username/psw/words) prima di usarli.

## 10. Consegna burocratica (facile da fallire)
- `javac` deve compilare da riga di comando → niente path IDE hard-coded.
- Nomi con `Main` per le classi con `main`.
- Due JAR eseguibili + due file di config + PDF ≤5 pag con sezioni obbligatorie
  (schema thread, strutture dati, primitive di sync, istruzioni esecuzione).
- Zip (non rar/gz) su Moodle.

---
### Dove pesa ~80% del lavoro (riassunto)
1. **Concorrenza server** (pool + game condiviso + scheduler + persistenza).
2. **Networking client** (NIO TCP + UDP async, due ricezioni concorrenti).
3. **Protocollo/JSON + regola malformata/errata** (robustezza messaggi).
4. **Logica di gioco + statistiche** (valutazione, scoring, streak, histogram).
Il resto (CLI, build, PDF) è volume ma a rischio basso.
