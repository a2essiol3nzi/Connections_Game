# `T` — micro-framework di assertion per le suite

## Ruolo
Struttura minima di check per le suite di test. Registra i FAIL in una lista
statica e li riassume alla fine; restituisce il numero di falliti. Nessuna
dipendenza esterna.

## Stato
- `FAILURES` — `static final List<String>` cumulativa (condivisa tra le suite).

## Metodi (statici)
- `section(String)` — stampa intestazione `=== name ===`.
- `check(label, Response, expectStatus, expectErrorCode, Predicate<Response>)` —
  verifica `status`, `errorCode`, e/o una condizione extra sul payload.
  Registra label in `FAILURES` + stampa il `payload` in caso di fail.
- `ok(label, Response)` — shortcut: `check(..., "OK", null, null)`.
- `err(label, Response, errorCode)` — shortcut: `check(..., "ERROR", code, null)`.
- `cond(label, boolean)` — check booleano arbitrario.
- `summary()` — stampa `==== FAILURES: N ====` + l'elenco; ritorna `N`.

## Note
Le suite accumulano in `T.FAILURES` e a fine `run()` ritornano
`T.FAILURES.size()` (incremental o assoluto a seconda della suite):
`TestLoader` salva un `base` per contare solo i propri fail.

## Collegamenti
- Usato da `TestFunc`, `TestLoad`, `TestStats`, `TestLoader`.