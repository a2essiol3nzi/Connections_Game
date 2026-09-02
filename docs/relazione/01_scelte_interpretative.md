# Scelte nei punti lasciati all'interpretazione personale

Il bando definisce funzionalità e protocollo, ma lascia aperti alcuni vincoli di progetto. Questo capitolo dichiara, in modo esplicito, come sono state risolte tali scelte nell'implementazione consegnata.

## Metrica della classifica

Il bando richiede una classifica dei giocatori ma non specifica quale valore ordini la graduatoria. È stata scelta la **somma cumulativa dei punteggi di tutte le partite giocate** (`cumulativeScore`), aggiornata a ogni partita conclusa. Un ordine alternativo (es. media, best score, win-rate) avrebbe penalizzato i giocatori meno attivi o premiato il "lotto"; la somma cumulativa è la metrica più semplice e direttamente confrontabile.

## Durata di una partita

Non è fissata dal bando. È **configurabile** tramite `game.duration.sec` in `server.properties` (default 600 s); l'assetto consegnato usa 180 s per permettere più cicli di verifica in tempi brevi. Lo scheduler, scaduto il tempo, finalizza la partita in modo **idempotente** (una sola volta) e procede alla rotazione.

## Passaggio tra una partita e la successiva

Il bando non precisa cosa accade a fine round. È stato scelto che la rotazione **sia immediata** e che i giocatori **online** (traccia in `onlineUsers`) vengano **ri-iscritti automaticamente** alla nuova partita (`GameManager.rotate`). La nuova partita parte senza attendere nuove connessioni; chi si collega dopo l'avvio può comunque partecipare finché la partita corrente non è finita.

## Memorizzazione delle password

Il bando non affronta la sicurezza. Le password sono memorizzate **in chiaro** dentro `users.json`. Scelta consapevole e dichiarata: il progetto è didattico e non tocca temi di autenticazione robusta; in un sistema reale andrebbero usati hash + salt.

## Libreria JSON

È stata adottata **Gson 2.11.0** (jar allegato alla consegna), sia per il server sia per il client.

## Codici di errore

Il protocollo definisce lo *shape* dell'errore ma non il set di codici. I codici sono **testuali** (`ERR_*`), **centralizzati** nell'enum `protocol.Errors` e condivisi da server e client. Su uno stato "vuoto" si usa la convenzione `null = OK`. Questo evita stringhe sparse e garantisce che client e server parlino la stessa lingua di errori.

## Identificatore della partita corrente: il sentinel `-1`

Il bando lascia aperto come il client richieda la partita "corrente" rispetto a una storica. È stato scelto un **sentinel**: nel campo `Request.roundId`, il valore `-1` (default) significa *partita corrente/live*; un valore positivo diverso dall'attuale indica una *partita conclusa* (storico). Il server risolve il sentinel con `currentFor(roundId)`.

## Persistenza: due file JSON distinti

Lo stato persistente è scisso in due file indipendenti:
- `users.json` — credenziali e statistiche dei giocatori;
- `history.json` — storico delle partite concluse (esiti e punteggi).

La separazione rende i due domini (identità vs. storico) salvabili con frequenze e trigger diversi senza interdipendenza.

## Ordinamento delle partite (sequenziale ciclico)

L'insieme di partite in `games.json` viene servito in **ordine sequenziale ciclico**: il loader non carica il file in memoria ma lo scorre in streaming con `JsonReader` a **memoria O(1)**, ripetendolo ciclicamente all'esaurimento (`CyclicGameIterator`). Questa scelta evita il costo e i problemi di un indice in RAM e rende il file delle partite semplice da mantenere a mano.

La posizione nel ciclo è inoltre **persistita tra i riavvii**. A ogni partita è assegnato un `roundId` monotono, e la sequenza è deterministica (`0..total-1` poi wrap): al boot `GameManager.resumeFromRound()` riallinea l'iteratore a `(nextRoundId-1) % total` (= `roundId % total`, dove `roundId` è il numero di round già giocati, ricostruito da `loadHistory` dal massimo dello storico). Così, dopo uno shutdown e riavvio, si riparte dalla partita **successiva a quella già mostrata**, non dalla prima del file. Questo evita peraltro di "spoilerare" l'ordine: chi conoscesse il file non può predire le partite future perché la sequenza riprende dal punto esatto di interruzione.

