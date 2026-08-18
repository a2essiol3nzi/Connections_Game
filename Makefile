# Makefile — compilazione server Connections (Java)
# Uso:  make          (compila in out/)
#       make run      (compila + lancia server.core.ServerMain)
#       make clean    (rimuove out/)

JAVAC  := javac
JAVA   := java
SRC    := src
OUT    := out
LIB    := lib/gson-2.11.0.jar
MAIN   := server.core.ServerMain
CP     := $(OUT):$(LIB)

SOURCES := $(shell find $(SRC) -name '*.java')

# ricompila solo se una sorgente è cambiata
$(OUT)/.stamp: $(SOURCES)
	$(JAVAC) -cp '$(CP)' -d $(OUT) $(SOURCES)
	touch $@

all: $(OUT)/.stamp

run: all
	$(JAVA) -cp '$(CP)' $(MAIN)

clean:
	rm -rf $(OUT)

.PHONY: all run clean
