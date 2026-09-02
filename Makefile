# Makefile — compila ed esegue la Connections Game (server + client)
#
# Comandi:
#   make            compila tutto in out/
#   make run        compila e avvia il SERVER (server.core.ServerMain)
#   make run-client compila e avvia il CLIENT (client.ClientMain)
#   make jar        compila e crea i JAR in dist/ (server + client)
#   make clean      cancella out/ e dist/

JAVAC := javac
JAVA  := java
JAR   := jar
SRC   := src
OUT   := out
DIST  := dist
LIB   := lib/gson-2.11.0.jar
CP    := $(OUT):$(LIB)

# Tutte le sorgenti Java del progetto
SOURCES := $(shell find $(SRC) -name '*.java')

# Compila in out/
compile:
	mkdir -p $(OUT)
	$(JAVAC) --release 8 -cp '$(CP)' -d $(OUT) $(SOURCES)

# Un JAR = un file .class raggruppato, per lanciarlo con `java -jar`.
# Il "manifest" dice: main class da avviare e dove trovare gson
# (che resta fuori, in lib/).
jar: compile
	mkdir -p $(DIST)
	printf 'Main-Class: server.core.ServerMain\nClass-Path: ../$(LIB)\n' > $(OUT)/manifest-server
	$(JAR) --create --file $(DIST)/connections-server.jar --manifest=$(OUT)/manifest-server -C $(OUT) .
	printf 'Main-Class: client.ClientMain\nClass-Path: ../$(LIB)\n' > $(OUT)/manifest-client
	$(JAR) --create --file $(DIST)/connections-client.jar --manifest=$(OUT)/manifest-client -C $(OUT) .

run: compile
	java -cp '$(CP)' server.core.ServerMain

run-client: compile
	java -cp '$(CP)' client.ClientMain

clean:
	rm -rf $(OUT) $(DIST)

.PHONY: compile jar run run-client clean