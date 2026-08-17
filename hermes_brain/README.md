# Connections — Second Brain

Progetto fine corso Reti/Lab III (A.A. 2025/26), versione specifica 1.1.
Gioco "Connections" (NYT) implementato in **Java** con architettura **client-server**.

> Stato repo al 17/08/2026: **greenfield**. Nessun `.java` presente. Solo
> `docs/Lab3_MD/progetto_v1_1.md`, immagini, PDF e `Lab3_Progetto.zip`.
> Il file JSON delle 911 partite (citato in §2.2 come "allegato") **manca**.
> Vedi `status.md` → blocco S2.

## Stack e vincoli tecnologici (da §3, ordinamento NUOVO)
- Java, compilazione con `javac` (no IDE files in consegna).
- Client: **CLI** (GUI facoltativa, non valutata).
- **Registrazione via TCP** (no RMI — quello è vecchio ordinamento).
- Comunicazione client↔server su **TCP persistente**, messaggi **JSON** (§5).
- Client deve usare **NIO** per la connessione TCP.
- Server **multithreaded con thread pooling**.
- Strutture dati server **sincronizzate**.
- Notifiche asincrone (es. fine partita a timeout) via **UDP**.
- Persistenza (utenti + partite) in file **JSON**.
- **No multicast UDP** (solo vecchio ordinamento) → notifiche UDP unicast.
- Consegna: JAR eseguibile client + JAR eseguibile server, file di config
  separati (client/server), NO parametri interattivi/CLI. PDF relazione ≤5 pag.

## Vincoli di naming (§4)
- Classi con `main` → nome contenente `"Main"` (es. `ServerMain`, `ClientMain`).
- Codice commentato. Librerie esterne → allegate come jar.

## Decisioni lasciate all'interpretazione (DA DOCUMENTARE nella relazione PDF)
Da definire e giustificare nel report (§4 richiede "scelte effettuate nei punti
lasciati alla personale interpretazione"):
1. **Metrica classifica** (cumulative score? win rate? n. vittorie?).
2. **Durata partita** → default 10 min (citato in §2.2), da config.
3. **Distanza tra una partita e la successiva** (subito / gap).
4. **Password**: NON in chiaro → hash + salt (sicurezza al boundary).
5. **Libreria JSON**: Gson/Jackson (attach jar) vs parser minimo hand-rolled.
6. **Codici di errore** numerici/testuali per ogni operazione (§5 lascia aperto).
7. **Sentinel "partita corrente"** nel campo `gameId` INT (es. -1).
8. **Persistenza partita in corso** (o solo storico + utenti).
9. **Outcome per giocatore** ∈ {WON, LOST(4 errori), NOT_FINISHED(timeout)}.
10. **Mistake Histogram**: bin vittorie con 0-3 errori + fallite(4) + not_finished.

## Convenzioni adottate qui
- Lingua: italiano (match progetto/utente).
- File di questo brain: `README.md` (indice), `status.md` (board),
  `protocol.md` (messaggi/errori), `gotchas.md` (insidie + dove pesa il lavoro).
