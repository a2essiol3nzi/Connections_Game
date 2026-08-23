# Analisi qualità del codice + sincronizzazione — Server Connections

> Companion di `docs/analisi_concorrenza.md`. Quella copre i **race/bug di
> flusso**; questa copre **codice di bassa qualità, ridondanze, sync
> mal implementata e punti migliorabili**. Analisi statica `src/server/**`.
> Nessuna modifica applicata.

Severità: 🔴 sbagliato/pericoloso · 🟡 fragile/ridondante · 🟢 stile/minore.
Ordine per file, poi per occorrenza.

---

## GameManager

**🟡 Q1 — `synchronized(g)` morto in `finalizeGame` (riga 354).**
Il metodo è già `synchronized(this)`; dentro il loop riprende `synchronized(g)`
per ogni player. Ma `g.finalized` è già `true` (settato a riga 346) → nessun
`submit` può più mutare `PlayerState`. Il lock su `g` non protegge nulla: è
rumore che fa chiedere al lettore "perché ri-lockare?". Sync ridondante.

**🟡 Q2 — Sigillo `finalized.set(true)` fuori dal lock di `g` (riga 346).**
Il set avviene sotto `synchronized(this)` ma NON `synchronized(g)`. Un `submit`
concorrente può essere già **dentro** `synchronized(g)`, avendo superato
`if(finalized.get())`: sigillo e valutazione-in-corso si sovrappongono, quel
submit viene contato. `AtomicBoolean` dà visibilità ma non esclude la submit già
in volo. È la radice sync delle race #1/#4 di `analisi_concorrenza.md`.

**🟡 Q3 — Difesa asimmetrica su `getState` null.**
`gameStats(-1)` (riga 243) fa `ps.finished` presumendo `ps != null`; `gameInfo`
(riga 178) invece controlla `ps != null`. `participants()` deriva da
`players.keySet()` quindi oggi `getState` non è mai null in `gameStats` — ma i
due metodi gemelli si contraddicono. Se `participants`/`players` divergessero,
`gameStats` fa NPE.

**🟢 Q4 — `Random rnd` condiviso.** Usato solo in `makeNext` (confinato allo
scheduler) → ok. `java.util.Random` è comunque sync internamente. Confinamento
non documentato. YAGNI: lasciare.

**🟡 Q5 — CHM annidata inutile: `GameHistory.entries` è `ConcurrentHashMap`.**
Scritto solo in `finalizeGame` (single-thread, sotto `synchronized(this)`), letto
via `get` su `history`. Una `HashMap` basterebbe: paghi il costo CHM senza
contesa. Sync sovradimensionata.

**🟡 Q6 — `trimHistory` O(n) a ogni put oltre cap (righe 396-399).**
Scansiona tutte le chiavi per il min a ogni round dopo il 1000°.
`ponytail:` O(n) scan, cap=1000 ⇒ 1000 iter/round ogni 600s = irrilevante.
Tenere il min o `TreeMap` se il cap crescesse. Lasciare, annotato.

**🟢 Q7 — `leaderboard`: doppio contenitore `all` + `rows` (righe 280-298).**
`allUsers()` dà già la lista; `all` serve solo per `all.get(i)` mentre si
ricostruisce `rows`. Si può iterare direttamente su `allUsers()`. Leggibilità.

**🟢 Q8 — `GSON` statico duplicato in 4 classi** (GameManager, UserStore,
ClientHandler, UdpNotifier), con config divergenti (`setPrettyPrinting` solo in
UserStore). Gson è thread-safe → non un bug; manca centralizzazione.

---

## ActiveGame

**🟡 Q9 — Incapsulamento rotto: `getState` espone `PlayerState` mutabile fuori lock.**
`getState`/`participants` sono `public` NON `synchronized`; ritornano il
riferimento diretto a `PlayerState`, i cui campi sono `public` mutabili. La
thread-safety dipende dalla **disciplina del chiamante** (che deve prendere
`synchronized(g)`), non dal tipo. È la debolezza sync **strutturale principale**:
niente impedisce una lettura/scrittura fuori lock.

**🟢 Q10 — `shuffledWords.contains(w)` O(16) in `submit` (riga 146).**
Dentro loop O(4) ⇒ O(64)/submit su una `List`. Un `Set` di tutte le 16 parole
darebbe O(1). YAGNI (16 parole), ma è l'algoritmo più fiacco a parità di codice.

**🟢 Q11 — `PlayerState.alreadyFound` mai usato.** Codice morto (grep).

---

## PlayerState

**🟡 Q13 — Campi `public` non-final mutabili senza incapsulamento.**
`errorCount`, `correctCount`, `finished`, `foundGroups` tutti `public`. La sync
vive interamente in ActiveGame via `synchronized(this)`. Accettabile per un DTO
interno **solo se** `getState` fosse package-private o restituisse copia
immutabile — non lo è (vedi Q9).

---

## UserStore

**🔴 Q14 — `synchronized(null)` NPE latente in `updateCredentials` (righe 136-137).**
`User u = byId.get(id); synchronized(u)` senza null-check. Oggi `u` non è null
(`byId`/`nameToId` coerenti sotto lock), ma `login` (riga 118) fa il null-check e
`updateCredentials` no: difesa asimmetrica su path gemelli. Se lo store fosse mai
incoerente → `synchronized(null)` NPE uccide l'update.

**🟡 Q15 — Doppio binario di sync: `synchronized(this)` + `ConcurrentHashMap`.**
`byId`/`nameToId` sono CHM, ma le scritture strutturali sono anche sotto
`synchronized(this)` (per atomicità multi-mappa: containsKey→put, putIfAbsent→
remove). `allUsers()`/`getById` leggono senza `this`, affidandosi alla CHM: ecco
perché restano CHM. Due meccanismi per lo stesso stato. Funziona, ma è la scelta
sync meno pulita: o CHM + `compute`/`merge` atomici (niente `this`), o HashMap +
solo monitor. Da spiegare nel PDF o semplificare.

**🟡 Q16 — `persist()` tiene `synchronized(this)` per TUTTA l'I/O (righe 183-214).**
Serializza JSON + `Files.move` col lock dello store preso, bloccando
`register`/`login`/`updateCredentials`/`getByName` per l'intera scrittura su
disco. Bottleneck più concreto del progetto: durante il flush nessuno si logga.
`ponytail:` lock-durante-I/O, snapshot-sotto-lock-poi-scrivi-fuori se la latenza
login conta. Persist ogni 30s + pochi utenti ⇒ oggi tollerabile.

**🟢 Q17 — `hasUser` mai usato.** Codice morto (grep).

**🟢 Q18 — `load()` legge tutto l'array in RAM** mentre i games usano streaming
pigro. Incoerenza di filosofia; utenti = "pochi" (dichiarato) ⇒ accettabile.

---

## ClientHandler

**🔴 Q19 — Usa `ERR_ALREADY_LOGGED_IN` che la doc dichiara RIMOSSO (riga 92).**
`hermes_brain/protocol.md`: "`ERR_ALREADY_LOGGED_IN` **rimosso**". Il codice lo
emette ancora, nella logica di doppio-login che è la race #1. Codice e doc
divergono: allineare (decidere se il doppio login è vietato — e allora renderlo
atomico, vedi #1 concorrenza — o permesso — e togliere l'errore).

**🟡 Q20 — `dispatch`: gate auth duplicato in 5 rami.**
`if (loggedInUserId == null) return ERR_NOT_LOGGED_IN` ripetuto 5 volte. Un check
unico prima dello switch per le op autenticate (o mappa op→requiresAuth)
toglierebbe 5 righe duplicate.

**🟡 Q21 — JSON malformato abbatte l'intera sessione persistente (riga 51).**
`GSON.fromJson(line, Request.class)` non ha try mirato: `JsonSyntaxException`
finisce nel `catch(Exception)` generico (riga 59) che **chiude la connessione**.
Un singolo frame rotto uccide la sessione invece di rispondere `BAD_REQUEST` e
proseguire. Il contratto è "connessione persistente": la gestione errore dovrebbe
essere **per-messaggio**, non per-connessione.

**🟢 Q22 — `handleProposal` ricalcola `gameInfo(-1)`** anche nel path OK; micro
ridondanza di lookup.

---

## GameScheduler

**🟢 Q23 — Coordinamento a `Thread.sleep(waitMs)` (riga 38).**
Se una partita finisse in anticipo (tutti vinto/perso) lo scheduler dorme
comunque fino a `endTimeMs`. Design a durata fissa ⇒ ok; è il punto dove
`wait/notifyAll` (permessi §3) darebbe fine-anticipata. Documentare.

**🟡 Q24 — I/O di persistenza sul cammino critico dello scheduler.**
`finalize→persist→persistHistory→UDP→rotate` sequenziale in un thread: se
`persist()` blocca su I/O lento, la rotazione e il round successivo slittano.
Accoppiamento temporale.

**🟡 Q25 — Gestore `InterruptedException` cosmetico (righe 59-61).**
`sleep` cattura, setta `interrupt()`, ritorna: il loop **prosegue** a
`finalizeGame` anche se interrotto a metà attesa. Nessuno interrompe oggi, ma il
gestore non ferma il ciclo → è decorativo.

---

## UdpNotifier

**🔴 Q26 — `notifyEnd(Set<Integer> participants, ...)` IGNORA `participants`.**
Invia UN solo datagramma a `loopback:udpPort` fisso (righe 25-27); il parametro
`participants` è preso e **mai usato**. Conseguenze:
- Non è unicast-per-partecipante (§2.2/§3): singolo pacchetto a porta fissa. Con
  >1 client sulla stessa macchina, solo il processo che ha bindato quella porta
  UDP lo riceve; gli altri no. Requisito "notifica a ogni partecipante" **non
  soddisfatto**.
- Manca del tutto la mappa `userId → endpoint UDP del client`. Il server non
  registra da dove/che porta ascolta ciascun client. Il client è P2 (non scritto),
  ma il notifier è **strutturalmente incompleto**: senza registro degli endpoint
  non può fare unicast mirato. `participants` iterato altrove solo per contare.

Difetto di qualità più serio insieme a Q19. La sync qui è irrilevante; è il
**design del notifier** ad essere un placeholder che finge di usare i
partecipanti.

**🟢 Q27 — Nuovo `DatagramSocket` per ogni `notifyEnd`.** Riusabile sarebbe più
pulito; 1 notifica/round/600s ⇒ irrilevante.

---

## Sintesi — dove la sincronizzazione è debole/migliorabile

| Tema sync | Dove | Giudizio |
|-----------|------|----------|
| Incapsulamento rotto: stato mutabile pubblico fuori lock | `getState`→`PlayerState` (Q9, Q13) | **Più debole strutturalmente.** Thread-safety per disciplina, non per tipo. |
| Doppio binario CHM + `synchronized(this)` | UserStore (Q15) | Ridondante. O compute-atomici, O HashMap+monitor. |
| Lock morto/ridondante | `finalizeGame` `sync(g)` (Q1) | Non protegge nulla dopo `finalized`. |
| Sigillo fuori dal lock giusto | `finalized.set` (Q2) | Submit in volo sfugge al sigillo (radice race #1/#4). |
| Lock tenuto durante I/O | `persist()` (Q16) | Bottleneck concreto: login bloccato durante flush. |
| CHM annidata inutile | `GameHistory.entries` (Q5) | Single-thread write ⇒ HashMap basta. |
| Interrupt cosmetico | Scheduler (Q25) | Non ferma il loop. |

## Sintesi — bassa qualità non-sync

| Sev | # | Problema |
|-----|---|----------|
| 🔴 | Q26 | `UdpNotifier` ignora `participants`: notifica non mirata, unicast non soddisfatto |
| 🔴 | Q19 | Usa `ERR_ALREADY_LOGGED_IN` che la doc dichiara rimosso |
| 🔴 | Q14 | `synchronized(null)` NPE latente in `updateCredentials` (login gemello ha il check) |
| 🟡 | Q20 | 5 gate auth duplicati in `dispatch` |
| 🟡 | Q21 | JSON malformato abbatte la sessione persistente invece di `BAD_REQUEST` |
| 🟡 | Q3 | Difesa null asimmetrica `gameStats` vs `gameInfo` |
| 🟡 | Q24 | I/O persistenza sul cammino critico dello scheduler |
| 🟢 | Q11,Q17 | Codice morto: `alreadyFound`, `hasUser` |
| 🟢 | Q7,Q8,Q10,Q18,Q22,Q23,Q27 | Ridondanze/stile minori |

**Quick win a basso rischio** (deletion/one-liner, se un domani si applica):
codice morto Q11/Q17 (cancella), Q1 lock morto (togli), Q7 doppio contenitore
(semplifica), Q20 gate unico (−5 righe). I 🔴 (Q14/Q19/Q26) sono decisioni di
design, non one-liner: vanno discussi prima.
