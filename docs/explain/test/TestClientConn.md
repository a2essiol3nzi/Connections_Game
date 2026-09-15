# `TestClientConn` — regressione framing UTF-8 del client NIO

## Ruolo
Verifica direttamente `client.ClientConn` contro un server TCP locale, senza
avviare il server di gioco. Protegge il framing della risposta JSON delimitata
da newline quando TCP divide un carattere UTF-8 multibyte tra due invii.

## Flusso (`run`)
1. Apre un `ServerSocket` effimero e avvia un peer locale.
2. Il peer legge la richiesta fino a `\n`.
3. Invia una `Response` JSON con `message: "€"`, separando il primo byte di
   `€` dai due byte restanti.
4. `ClientConn.sendAndRetreive()` deve restituire la stringa integra `€`.

## Dettagli
- Il peer propaga eventuali errori al test mediante `AtomicReference<Throwable>`.
- Il test restituisce `1` se l'asserzione fallisce, così `RunAll` termina con
  esito non nullo.

## Collegamenti
- `client/ClientConn`: componente verificato.
- `test/RunAll`: invoca la regressione dopo le suite server.
