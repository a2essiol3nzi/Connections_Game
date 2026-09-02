# `TestLoad` — suite di carico/concorrenza

## Ruolo
Suite di **carico/concorrenza**: cerca colli di bottiglia e race sotto
pressione. Usa molti `TC` (un `ClientConn` per thread) su thread concorrenti.

## `run(port)` — 5 test (L1..L5)
- **L1 · login concorrenti** (16 utenti) — throughput (`~N login/s`); tutti i
  login devono riuscire (`l1fail==0`).
- **L2 · submit paralleli** (12 client × 4 submit) — verifica la **lock
  granulare su `PlayerState`**: le submit parallele non devono serializzarsi su
  un lock globale; un submit duplicato deve dare `ERR_MALFORMED`.
- **L3 · doppio login + relogin dopo EOF** — login da 2 connessioni →
  `ERR_ALREADY_LOGGED_IN`; chiusura della prima (EOF) sblocca l'utente e il
  relogin riesce.
- **L4 · leaderboard concorrenti** (16 client × 5 read) — check read-heavy:
  nessun errore sotto letture parallele (nessun lock globale necessario).
- **L5 · disconnect brusco** (15 client) — chiusura senza logout; il server
  resta attivo e i worker tornano al pool.

> Il numero di client concorrenti di L1/L4 è **`pool.size` (16)** del server di
> test (`TC.startServer`): con la politica `AbortPolicy` del pool on-demand chi
> supera il tetto viene disconnesso, quindi il carico di test non deve eccederlo.

## Concorrenza
Ogni thread usa un proprio `TC`/`ClientConn` (nessuna condizione condivisa tra
client). Gli accumulatori di fails sono `AtomicInteger`. `T.FAILURES` è la sola
struttura condivisa tra thread (append, safe).

## Collegamenti
- `TC`: client per thread.
- `T`: assertion.
- Copre i punti di attention di `hermes_brain` (lock granulare, `synchronized`
  su login/rotate, pool limitato).