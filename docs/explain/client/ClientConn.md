# `ClientConn` — connessione TCP persistente NIO

## Ruolo
Connessione **NIO** (requisito §3): `SocketChannel` in **modalità non bloccante**
+ `Selector`. Il server usa una riga JSON per ogni risposta (`\n`); `send()`
esegue una round-trip scrivendo la richiesta e leggendo la riga-risposta.

## Stato interno
- `chan` — `SocketChannel` (config non bloccante, `finishConnect` atteso via
  `sel.select()`).
- `sel`, `key` — `Selector` e `SelectionKey` (OP_READ all'avvio, OP_WRITE solo
  durante la scrittura).
- `netBuff` — `ByteBuffer` heap da 64 KiB, riusato per ogni `read()`.
- `lineBytes` — accumulatore di byte della riga parziale; la conversione UTF-8
  avviene solo dopo `\n`, quindi un carattere multibyte diviso fra due `read()`
  resta integro.
- `pendingLines` — coda delle righe complete già lette nello stesso `read()`.

## Metodo principale
- `Response sendAndRetreive(Request req)` — **`synchronized`**: un unico lock
  copre scrivi+leggi, così il thread CLI e il fetch dall'`UdpClient` non
  intrecciano le righe. Scrive `GSON.toJson(req)+"\n"`, poi `readLine()`.
  (Nella regione critica la round-trip richiesta/risposta è atomica rispetto
  agli altri chiamanti.)
- `writeAll(ByteBuffer)` (priv) — scrive finché non ha finito; se `write()==0`
  passa a OP_WRITE + `sel.select()`, poi torna a OP_READ. **NOTA anti busy-spin**:
  l'interesse WRITE non resta attivo — un SocketChannel è (quasi) sempre
  scrivibile, quindi un `select()` rientrerebbe subito e `readLine()` andrebbe in
  busy-spin (100% CPU) invece di attendere la risposta.
- `readLine()` (priv) — accumula byte fino a `\n`, decodifica allora la riga
  UTF-8 e restituisce `Response`; conserva in `pendingLines` eventuali righe
  successive già ricevute. Se manca il delimitatore legge dal canale (non
  bloccante, `0`→`sel.select()`); oltre 64 KiB senza newline lancia
  `IOException`. `read()<0` ⇒ `IOException("server chiuso")`.
- `close()` — chiude canale e selector.

## Concorrenza
Nessun pool client. L'unico lock è `synchronized (conn)` su
`sendAndRetreive()`: serializza il thread CLI (main) con il fetch del
`UdpClient` (`printResult`).

## Collegamenti
- `client/Cli`: chiama `sendAndRetreive()` nel loop.
- `client/UdpClient`: riusa `sendAndRetreive()` per leggere l'esito dopo
  `GAME_ENDED`.