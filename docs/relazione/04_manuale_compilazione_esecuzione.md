# Manuale di compilazione ed esecuzione

## Struttura del progetto

```
Connections_Game/
├── docs/               # relazione e modelli del sistema
├── src/                # sorgenti Java
│   ├── client/         # ClientMain, ClientConfig, ClientConn, UdpClient, Cli
│   ├── protocol/       # Request, Response, Errors, GameEnded, payload/*
│   └── server/         # core/, loader/, model/, network/, persistence/
├── lib/gson-2.11.0.jar # dipendenza esterna allegata
├── data/               # games.json (partite), users.json, history.json (a runtime)
├── dist/               # JAR eseguibili (da build.sh / make jar)
├── scripts/            # build.sh, run-server.sh, run-client.sh (build/avvio veloci)
├── server.properties   # configurazione server
├── client.properties   # configurazione client
└── Makefile            # compile / jar / run / run-client / clean
```

## Compilazione

È richiesto un JDK 8 o superiore (il codice è compilato con `--release 8` come da specifica del corso); il progetto si compila da riga di comando, senza IDE:

```
make            # compila tutto in out/
make jar        # genera anche i JAR eseguibili in dist/
```

I target del `Makefile` sono: `compile`, `jar`, `run`, `run-client`, `clean`. La dipendenza Gson è già al path `lib/gson-2.11.0.jar` e viene agganciata via `-cp` (e nel `Class-Path` del manifest dei JAR).

### Build e run rapidi con gli script

Nella cartella `scripts/` sono disponibili tre script di uso immediato:

- `scripts/build.sh` - esegue `make clean` + `make`, compila tutto in `out/` e genera i JAR in `dist/`, con output colorato e conteggio delle classi compilate.
- `scripts/run-server.sh` - avvia il server dal JAR `dist/connections-server.jar` (richiede che l'abbia già generato `build.sh`).
- `scripts/run-client.sh` - avvia il client dal JAR `dist/connections-client.jar` (idem).

Uso tipico: `./scripts/build.sh` la prima volta, poi `./scripts/run-server.sh` per far partire il server e `./scripts/run-client.sh` in un altro terminale per il client.

## Esecuzione del server

Configurazione in `server.properties` (letta all'avvio, nessun parametro interattivo).
Avvio (dal JAR, col `Main-Class` `server.core.ServerMain`):

```
make run                                # da Makefile
java -jar dist/connections-server.jar   # dal JAR eseguibile
```

## Esecuzione del client

Configurazione in `client.properties`: solo `host` e `port` (TCP). La porta UDP **non** è configurabile: è una porta **effimera** scelta a runtime dal receiver e comunicata al server dentro il comando `login`.
Avvio:

```
make run-client                          # da Makefile
java -jar dist/connections-client.jar    # dal JAR eseguibile
```

> In generale, sia per client che server, usare gli scripts forniti è più semplice e preferibile. 
