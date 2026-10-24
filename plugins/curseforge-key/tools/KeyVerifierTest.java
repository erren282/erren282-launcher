package org.dsh.cfkey;

/**
 * Verifies the key check actually distinguishes a working key from a made-up one.
 *
 * <h2>The bug this pins down</h2>
 * <p>The first version of {@link KeyVerifier} called {@code /v1/categories} and treated
 * "the request did not throw" as success. Measured, that endpoint answers <em>without any
 * key at all</em>, so every string — including a made-up one — was reported as valid, got
 * saved, and the user was then left with a source that 403s on every search. The check now
 * uses the search endpoint, which genuinely requires the header.</p>
 *
 * <p>These checks need the network. When CurseForge cannot be reached the verdict is
 * {@code UNREACHABLE}, and the checks that depend on a real answer are skipped rather than
 * reported as failures — being offline says nothing about the code.</p>
 */
public final class KeyVerifierTest {

    private static int passed;
    private static int failed;
    private static int skipped;

    public static void main(String[] args) {
        emptyInputChecks();
        garbageKeyChecks();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed, %d skipped%n", passed, failed, skipped);
        System.exit(failed > 0 ? 1 : 0);
    }

    /// Empty input must be refused without any network call.
    private static void emptyInputChecks() {
        System.out.println("=== empty input is refused locally ===");

        KeyVerifier.Outcome empty = KeyVerifier.verify("");
        check("an empty key is rejected", empty.verdict() == KeyVerifier.Verdict.REJECTED);
        check("it is not reported as merely unreachable",
                empty.verdict() != KeyVerifier.Verdict.UNREACHABLE);

        KeyVerifier.Outcome blank = KeyVerifier.verify("   ");
        check("a whitespace-only key is rejected",
                blank.verdict() == KeyVerifier.Verdict.REJECTED);

        KeyVerifier.Outcome nullKey = KeyVerifier.verify(null);
        check("a null key is rejected", nullKey.verdict() == KeyVerifier.Verdict.REJECTED);

        check("none of them is reported usable",
                !empty.isValid() && !blank.isValid() && !nullKey.isValid());
    }

    /// A made-up key must not be accepted.
    private static void garbageKeyChecks() {
        System.out.println();
        System.out.println("=== a made-up key is not accepted ===");

        KeyVerifier.Outcome outcome = KeyVerifier.verify("definitely-not-a-real-key-0000");
        System.out.println("  verdict: " + outcome.verdict()
                + (outcome.detail().isEmpty() ? "" : "  (" + outcome.detail() + ")"));

        switch (outcome.verdict()) {
            case REJECTED -> {
                check("the made-up key is rejected", true);
                check("it is not usable", !outcome.isValid());
                check("the message explains why", !outcome.detail().isEmpty());
            }
            case UNREACHABLE -> {
                // Offline: the check could not run, which is not a code failure.
                System.out.println("  (skipped: CurseForge unreachable)");
                skip("a made-up key is rejected");
                skip("it is not usable");
                skip("the message explains why");
            }
            case VALID -> check("the made-up key is rejected", false);
        }

        System.out.println();
        System.out.println("=== a rejected key does not throw ===");
        check("verify() returns an outcome rather than throwing", outcome != null);
        check("the outcome carries a verdict", outcome.verdict() != null);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private static void skip(String what) {
        skipped++;
        System.out.println("  [skip] " + what);
    }
}
