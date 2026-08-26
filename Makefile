# Makefile - compilazione server Connections (Java)
# Uso:  make          (compila in out/)
#       make dist     (compila + crea JAR in dist/)
#       make run      (compila + lancia server.core.ServerMain)
#       make run-client (compila + lancia client.ClientMain, quando esiste)
#       make clean    (rimuove out/ e dist/)

JAVAC  := javac
JAVA   := java
JAR    := jar
SRC    := src
OUT    := out
DIST   := dist
LIB    := lib/gson-2.11.0.jar
CP     := $(OUT):$(LIB)
MAIN   := server.core.ServerMain
CLIENT_MAIN := client.ClientMain
# --release 8: vincola sorgente + API alla versione 8 (requisito progetto)
RELEASE := --release 8
SOURCES := $(shell find $(SRC) -name '*.java')

$(OUT)/.stamp: $(SOURCES)
	$(JAVAC) $(RELEASE) -cp '$(CP)' -d $(OUT) $(SOURCES)
	touch $@

all: $(OUT)/.stamp

# JAR server (thin): gson resta in lib/, allegato a parte
# Nota: Class-Path è relativo alla posizione del JAR (dist/) -> ../lib/
dist/connections-server.jar: all
	mkdir -p $(DIST)
	printf 'Main-Class: $(MAIN)\nClass-Path: ../$(LIB)\n' > $(OUT)/manifest.txt
	$(JAR) --create --file $@ --manifest=$(OUT)/manifest.txt -C $(OUT) .

# JAR client: per ora non implementato
dist/connections-client.jar: all
	@if [ ! -d $(SRC)/client ]; then \
	    echo "❌ src/client non esiste ancora: client non implementato"; exit 1; \
	fi
	$(JAR) --create --file $@ --main-class $(CLIENT_MAIN) -C $(OUT) .

run: all
	$(JAVA) -cp '$(CP)' $(MAIN)

run-client: dist/connections-client.jar
	$(JAVA) -cp 'dist/connections-client.jar:$(LIB)' $(CLIENT_MAIN)

clean:
	rm -rf $(OUT) $(DIST)

.PHONY: all run run-client clean
