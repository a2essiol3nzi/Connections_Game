import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Micro-framework di assertion per le suite di test.
 * Registra i FAIL e li riassume alla fine; esco con exit code non-0 se ci sono falliti.
 */
public final class T {

    private T() {}

    public static final List<String> FAILURES = new ArrayList<>();

    public static void section(String name) {
        System.out.println("\n=== " + name + " ===");
    }

    // Verifica una Response per status e/o errorCode e/o campo payload.
    public static boolean check(String label, protocol.Response r,
                                String expectStatus, String expectErrorCode,
                                Predicate<protocol.Response> extra) {
        boolean ok = true;
        StringBuilder why = new StringBuilder();
        if (expectStatus != null && !expectStatus.equals(r.status)) {
            ok = false; 
            why.append(" status=").append(r.status).append("!=").append(expectStatus);
        }
        if (expectErrorCode != null && !expectErrorCode.equals(r.errorCode)) {
            ok = false; 
            why.append(" errorCode=").append(r.errorCode).append("!=").append(expectErrorCode);
        }
        if (extra != null && !extra.test(r)) {
            ok = false; 
            why.append(" payload-condition-failed");
        }
        if (ok) { 
            System.out.println("  ok  " + label); 
            return true; 
        }
        FAILURES.add(label);
        System.out.println("  FAIL " + label + ":" + why + " resp=" + r.payload);
        return false;
    }

    // Verifica solo status==OK.
    public static boolean ok(String label, protocol.Response r) {
        return check(label, r, "OK", null, null);
    }

    // Verifica status==ERROR e errorCode esatto.
    public static boolean err(String label, protocol.Response r, String errorCode) {
        return check(label, r, "ERROR", errorCode, null);
    }

    // Verifica una condizione booleana arbitraria.
    public static boolean cond(String label, boolean b) {
        if (b) { 
            System.out.println("  ok  " + label); 
            return true; 
        }
        FAILURES.add(label);
        System.out.println("  FAIL " + label);
        return false;
    }

    public static int summary() {
        System.out.println("\n==== FAILURES: " + FAILURES.size() + " ====");
        for (String f : FAILURES) 
            System.out.println("  - " + f);
        return FAILURES.size();
    }
}