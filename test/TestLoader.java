package test;

import server.loader.GameLoader;
import server.model.GameData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Suit ROBUSTEZZA del GameLoader (unità di test, nessun server).
 *
 * Verifica la progettazione del loader pigro-ciclico:
 *  - total() = conteggio strutturale (skipValue) che tollera voci "rotte" ma valide come JSON.
 *  - cyclicIterator().next() = ordine sequenziale + wrap ciclico all'EOF.
 *  - robustezza RUNTIME: una voce con data-binding fallito (gameId non numerico)
 *    viene SALTATA (catch JsonSyntaxException -> reopen -> skip) senza crash,
 *    e l'iterazione riprende dalla successiva.
 *
 * Fixture: test/sub-game.json (6 partite valide, gameId 0..5).
 * I file corrotti vengono generati in /tmp: sub-game.json resta pulito.
 */
public class TestLoader {

    public static int run(String projectDir) throws Exception {
        int base = T.FAILURES.size();
        String sub = projectDir + "/test/sub-game.json";

        T.section("Loader: integrita' + ordine ciclico (6 partite valide)");
        GameLoader l = new GameLoader(sub);
        T.cond("total() = 6", l.total() == 6);

        List<Integer> order = new ArrayList<>();
        GameLoader.CyclicGameIterator it = l.cyclicIterator();
        for (int i = 0; i < 6; i++) order.add(it.next().gameId);
        T.cond("ordine sequenziale 0..5", order.equals(java.util.Arrays.asList(0,1,2,3,4,5)));
        int seventh = it.next().gameId;
        T.cond("wrap ciclico: 7mo = 0", seventh == 0);

        // ---- robustezza: voce con data-binding rotto (gameId stringa) ----
        T.section("Loader: robustezza (salta voce con data-binding rotto)");
        String badFile = "/tmp/loader_bad_semantic.json";
        writeCorrupted(sub, badFile); // gameId 2 -> "NOTANUMBER"
        GameLoader lb = new GameLoader(badFile);
        // total() deve PASSARE: skipValue non fa data-binding, conta 6
        T.cond("total() = 6 anche con voce rotta", lb.total() == 6);

        // next() deve SALTARE gameId 2 e restituire 0,1,3,4,5, poi wrap 0
        int[] got = new int[6];
        GameLoader.CyclicGameIterator itb = lb.cyclicIterator();
        for (int i = 0; i < 6; i++) got[i] = itb.next().gameId;
        T.cond("salta voce rotta: [0,1,3,4,5,0]", java.util.Arrays.equals(got,
                new int[]{0,1,3,4,5,0}));
        // l'iteratore deve continuare a funzionare dopo (ciclico, non muore)
        int afterWrap = itb.next().gameId;
        T.cond("continua dopo il salto (ciclico)", afterWrap == 1 || afterWrap == 3);

        // ---- prima voce rotta ----
        T.section("Loader: robustezza (prima voce rotta)");
        String firstBad = "/tmp/loader_bad_first.json";
        writeCorruptedFirst(sub, firstBad); // gameId 0 -> roto
        GameLoader lf = new GameLoader(firstBad);
        int[] got2 = new int[6];
        GameLoader.CyclicGameIterator itf = lf.cyclicIterator();
        for (int i = 0; i < 6; i++) got2[i] = itf.next().gameId;
        T.cond("salta prima rotta: [1,2,3,4,5,1]", java.util.Arrays.equals(got2,
                new int[]{1,2,3,4,5,1}));

        // ---- ultima voce rotta ----
        T.section("Loader: robustezza (ultima voce rotta)");
        String lastBad = "/tmp/loader_bad_last.json";
        writeCorruptedLast(sub, lastBad); // gameId 5 -> roto
        GameLoader ll = new GameLoader(lastBad);
        int[] got3 = new int[6];
        GameLoader.CyclicGameIterator itl = ll.cyclicIterator();
        for (int i = 0; i < 6; i++) got3[i] = itl.next().gameId;
        T.cond("salta ultima rotta: [0,1,2,3,4,0]", java.util.Arrays.equals(got3,
                new int[]{0,1,2,3,4,0}));

        // ---- gruppo mancante (3/4): ora VALIDATO e saltato ----
        T.section("Loader: gruppo mancante (3/4) saltato dopo fix");
        String f3g = "/tmp/loader_3groups.json";
        writeMissingGroup(sub, f3g, 2); // gameId 2 -> solo 3 gruppi
        GameLoader l3 = new GameLoader(f3g);
        GameLoader.CyclicGameIterator it3 = l3.cyclicIterator();
        int[] got3g = new int[8];
        for (int i = 0; i < 8; i++) got3g[i] = it3.next().gameId;
        // gameId 2 deve essere SALTATO: [0,1,3,4,5,0,1,3]
        T.cond("salta gruppo mancante: no 2 in [0,1,3,4,5,0,1,3]",
                java.util.Arrays.equals(got3g, new int[]{0,1,3,4,5,0,1,3}));
        T.cond("total() resta 6 (skip a runtime, non al conteggio)", l3.total() == 6);

        // ---- tema mancante su un gruppo: ora VALIDATO e saltato ----
        T.section("Loader: tema mancante su un gruppo saltato dopo fix");
        String fnt = "/tmp/loader_notheme.json";
        writeMissingTheme(sub, fnt, 3); // gameId 3 -> un gruppo senza theme
        GameLoader ln = new GameLoader(fnt);
        GameLoader.CyclicGameIterator itn = ln.cyclicIterator();
        int[] gotNt = new int[8];
        for (int i = 0; i < 8; i++) gotNt[i] = itn.next().gameId;
        // gameId 3 deve essere SALTATO: [0,1,2,4,5,0,1,2]
        T.cond("salta tema mancante: no 3 in [0,1,2,4,5,0,1,2]",
                java.util.Arrays.equals(gotNt, new int[]{0,1,2,4,5,0,1,2}));
        T.cond("total() resta 6", ln.total() == 6);

        // ---- parola null in un gruppo: ora VALIDATO e saltato (prima NPE in ActiveGame) ----
        T.section("Loader: parola null in un gruppo saltata (evita NPE a runtime)");
        String fnull = "/tmp/loader_nullword.json";
        writeNullWord(sub, fnull, 4); // gameId 4 -> una parola diventa null
        GameLoader lw = new GameLoader(fnull);
        GameLoader.CyclicGameIterator itw = lw.cyclicIterator();
        int[] gotNw = new int[8];
        for (int i = 0; i < 8; i++) gotNw[i] = itw.next().gameId;
        // gameId 4 deve essere SALTATO: [0,1,2,3,5,0,1,2]
        T.cond("salta parola null: no 4 in [0,1,2,3,5,0,1,2]",
                java.util.Arrays.equals(gotNw, new int[]{0,1,2,3,5,0,1,2}));
        T.cond("total() resta 6", lw.total() == 6);

        return (T.FAILURES.size() - base) == 0 ? 0 : 1;
    }

    /** Legge dalla testa dell'iteratore finche' non incontra gameId target (wrap: attraversa tutto). */
    private static GameData consumeThroughId(GameLoader.CyclicGameIterator it, int target) throws Exception {
        for (int i = 0; i < 100; i++) {
            GameData d = it.next();
            if (d.gameId == target) return d;
        }
        return null;
    }

    /** Imposta a null una parola del PRIMO gruppo della voce con gameId==target. */
    private static void writeNullWord(String src, String dst, int targetId) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
        int start = indexOfGame(text, targetId);
        // trova la prima "words" dopo la voce
        int wordsIdx = text.indexOf("\"words\": [", start);
        if (wordsIdx < 0) throw new IllegalStateException("words non trovato per gameId " + targetId);
        int valStart = text.indexOf('"', wordsIdx + "\"words\": [".length());
        int valEnd = text.indexOf('"', valStart + 1) + 1;
        String out = text.substring(0, valStart) + "null" + text.substring(valEnd);
        Files.write(Paths.get(dst), out.getBytes(StandardCharsets.UTF_8));
    }

    /** Rimuove l'ULTIMO gruppo della voce con gameId==target (4 -> 3). */
    private static void writeMissingGroup(String src, String dst, int targetId) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
        int start = indexOfGame(text, targetId);
        int endIdx = text.indexOf("\n  ]}", start); // chiusura della voce (indentata in sub-game.json)
        if (endIdx < 0) throw new IllegalStateException("chiusura voce non trovata per gameId " + targetId);
        int objStart = text.lastIndexOf('{', start); // inizio della voce (il '{' prima del gameId)
        if (objStart < 0) throw new IllegalStateException("inizio voce non trovato per " + targetId);
        // taglia all'interno della voce: rimuove l'ultimo gruppo {...} (quello prima della chiusura "  ]}")
        int grpStart = text.lastIndexOf("\"theme\"", endIdx);
        int grpBracesBefore = text.lastIndexOf('{', grpStart);
        // la voce ha 4 gruppi ciascuno "{...},"; rimuovi l'ultimo blocco {...} (incl. virgola precedente)
        int lastComma = text.lastIndexOf(',', grpBracesBefore);
        String out = text.substring(0, lastComma) + text.substring(endIdx);
        Files.write(Paths.get(dst), out.getBytes(StandardCharsets.UTF_8));
    }

    /** Imposta a null il "theme" del PRIMO gruppo della voce con gameId==target. */
    private static void writeMissingTheme(String src, String dst, int targetId) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
        int start = indexOfGame(text, targetId);
        // il primo "theme" dopo la voce (primo gruppo di quella partita)
        int themeIdx = text.indexOf("\"theme\": ", start);
        if (themeIdx < 0) throw new IllegalStateException("theme non trovato per gameId " + targetId);
        int valStart = themeIdx + "\"theme\": ".length();
        int valEnd = text.indexOf(',', valStart);
        String out = text.substring(0, valStart) + "null" + text.substring(valEnd);
        Files.write(Paths.get(dst), out.getBytes(StandardCharsets.UTF_8));
    }

    private static int indexOfGame(String text, int targetId) {
        int i = text.indexOf("\"gameId\": " + targetId);
        if (i < 0) throw new IllegalStateException("gameId " + targetId + " non trovato");
        return i;
    }

    // Corrompe la voce con gameId == target: gameId diventa stringa -> NumberFormatException in GameData.
    private static void writeCorrupted(String src, String dst) throws IOException {
        writeCorruptedIdx(src, dst, 2);
    }
    private static void writeCorruptedFirst(String src, String dst) throws IOException {
        writeCorruptedIdx(src, dst, 0);
    }
    private static void writeCorruptedLast(String src, String dst) throws IOException {
        writeCorruptedIdx(src, dst, 5);
    }

    private static void writeCorruptedIdx(String src, String dst, int targetId) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
        String marker = "\"gameId\": " + targetId;
        int i = text.indexOf(marker);
        if (i < 0) throw new IllegalStateException("marker non trovato: " + marker);
        int colon = text.indexOf(':', i) + 1;
        String after = text.substring(colon);
        int comma = after.indexOf(',');
        String out = text.substring(0, colon) + " \"NOTANUMBER\"" + after.substring(comma);
        Files.write(Paths.get(dst), out.getBytes(StandardCharsets.UTF_8));
    }
}