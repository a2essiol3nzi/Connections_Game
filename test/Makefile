# Makefile dei test — non fa parte della consegna del progetto.
#
# Comandi (dalla root del progetto):
#   make -C test run         compila e lancia le suite (funzionale + carico)
#   make -C test clean      cancella test/out/
#
# Prerequisito: il codice in `out/` deve essere già compilato (`make` nella root),
# oppure RunAll lo compila da solo invocando `make` nella root del progetto.

JAVAC := javac
JAVA  := java
LIB   := $(abspath ../lib/gson-2.11.0.jar)
OUT   := $(abspath ../out)
TESTOUT := $(abspath out)
CP    := $(OUT):$(TESTOUT):$(LIB)

# Tutti i sorgenti di test nella directory corrente
TESTSOURCES := $(wildcard *.java)

# Compila i test in test/out/
compile:
	mkdir -p $(TESTOUT)
	$(JAVAC) --release 8 -cp '$(CP)' -d $(TESTOUT) $(TESTSOURCES)

# Compila e lancia le suite. RunAll usa la CWD come directory del progetto,
# quindi va eseguito dalla root (../): solo lì trova out/, lib/, data/.
run: compile
	cd .. && $(JAVA) -cp '$(CP)' RunAll

clean:
	rm -rf $(TESTOUT)

.PHONY: compile run clean