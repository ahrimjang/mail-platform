package io.github.ahrimjang.mail.common;

/**
 * Kind of recipient engagement recorded as an {@code EmailEvent}.
 * Engagement is event-derived and kept separate from delivery status.
 */
public enum EventType {
    OPEN,
    CLICK,
    BOUNCE,
}
