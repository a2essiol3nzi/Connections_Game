/**
 * Runner: compila (make), avvia il server su config temporanea, esegue le suite
 * funzionale e di carico, arresta il server. Exit 0 se tutto verde.
 *
 * Uso (dalla radice del progetto):
 *   java -cp 'out:test/out:lib/gson-2.11.0.jar' test.RunAll
 */
public class RunAll {

    public static void main(String[] args) throws Exception {
        String proj = new java.io.File(".").getCanonicalPath();
        boolean runFunc = !contains(args, "--load");
        boolean runLoad = !contains(args, "--func");

        System.out.println("[setup] build: make");
        Process build = new ProcessBuilder("make")
                .directory(new java.io.File(proj))
                .redirectErrorStream(true).inheritIO().start();
        int b = build.waitFor();
        if (b != 0) { System.err.println("[setup] build fallito"); System.exit(2); }

        System.out.println("[setup] avvio server su config temporanea ...");
        TC.Server srv = TC.startServer(proj, 180);
        int code = 0;
        try {
            if (runFunc) code += TestFunc.run(srv.tcpPort);
            if (runLoad) code += TestLoad.run(srv.tcpPort);
        } finally {
            System.out.println("\n[teardown] arresto server ...");
            srv.stop();
        }
        System.out.println("\n[client] TestClientConn: framing TCP");
        code += TestClientConn.run();
        // TestStats usa un server dedicato con durata di partita BREVE (rotazione rapida)
        System.out.println("\n[stats] TestStats: server dedicato (durata breve)");
        code += TestStats.run(proj);
        System.out.println("\n[loader] TestLoader: robustezza GameLoader unit");
        code += TestLoader.run(proj);
        System.exit(code == 0 ? 0 : 1);
    }

    private static boolean contains(String[] a, String v) {
        for (String s : a) if (s.equals(v)) return true;
        return false;
    }
}