package io.github.ahrimjang.mail.common;

/**
 * Outcome of a CSV contact import.
 *
 * @param imported number of new contacts created
 * @param skipped  number of lines skipped (invalid email or already existing)
 */
public record ImportResult(
        int imported,
        int skipped
) {
}
