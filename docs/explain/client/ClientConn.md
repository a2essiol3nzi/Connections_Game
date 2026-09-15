# `ClientConn` — connessione TCP persistente NIO bloccante

## Ruolo
Connessione **NIO** (requisito §3): un `SocketChannel` in modalità bloccante.
Il server usa una riga JSON per ogni risposta (`\n`); `send()` esegue una
round-trip scrivendo la richiesta e leggendo la riga-risposta.

## Stato interno
- `chan` — `SocketChannel` NIO in modalità bloccante, aperto e connesso nel
  costruttore.
- `netBuff` — `ByteBuffer` heap da 64 KiB, riusato per ogni `SocketChannel.read()`.
- `lineBytes` — accumulatore raw della riga parziale; UTF-8 è decodificato solo
  dopo `\n`, così un carattere multibyte diviso fra due read resta integro.
- `pendingLines` — coda delle righe complete già ricevute nello stesso read.

## Metodo principale
- `Response sendAndRetreive(Request req)` — **`synchronized`**: un unico lock
  copre scrivi+leggi, così il thread CLI e il fetch dall'`UdpClient` non
  intrecciano le righe. Scrive tutto il `ByteBuffer` di `GSON.toJson(req)+"\n"`
  e legge una riga tramite `SocketChannel.read()`.
  (Nella regione critica la round-trip richiesta/risposta è atomica rispetto
  agli altri chiamanti.)
- `writeAll(ByteBuffer)` (priv) — usa `SocketChannel.write()` bloccante fino a
  inviare l'intera richiesta.
- `readLine()` (priv) — usa `SocketChannel.read()` bloccante nel `ByteBuffer`;
  raccoglie byte fino a `\n`, decodifica la riga UTF-8 e conserva eventuali
  righe successive. EOF diventa `IOException`; oltre 64 KiB senza newline
  lancia `IOException`.
- `close()` — chiude il `SocketChannel`.

## Concorrenza
Nessun pool client. L'unico lock è `synchronized (conn)` su
`sendAndRetreive()`: serializza il thread CLI (main) con il fetch del
`UdpClient` (`printResult`).

## Collegamenti
- `client/Cli`: chiama `sendAndRetreive()` nel loop.
- `client/UdpClient`: riusa `sendAndRetreive()` per leggere l'esito dopo
  `GAME_ENDED`.