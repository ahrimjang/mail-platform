package io.github.ahrimjang.mail.core.service;

/**
 * Deterministic A/B variant assignment: a recipient always lands on the same
 * variant for a given split, so retries and re-expansion cannot flip anyone.
 */
public final class AbVariantAssigner {

    private AbVariantAssigner() {
    }

    /** @return "B" for roughly splitPercent% of recipients, "A" otherwise. */
    public static String assign(String recipientEmail, int splitPercent) {
        int bucket = Math.floorMod(recipientEmail.toLowerCase().hashCode(), 100);
        return bucket < splitPercent ? "B" : "A";
    }

    /**
     * Winner-flow assignment: only {@code testPercent}% of recipients enter the A/B
     * test (split between A and B by {@code splitPercent}); the rest return null and
     * are held back for the winning variant. Deterministic per email.
     */
    public static String assignWithHoldout(String recipientEmail, int testPercent, int splitPercent) {
        int bucket = Math.floorMod(recipientEmail.toLowerCase().hashCode(), 10_000);
        if (bucket >= testPercent * 100) {
            return null; // holdout — waits for the winner
        }
        return (bucket % 100) < splitPercent ? "B" : "A";
    }
}
