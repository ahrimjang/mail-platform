package io.github.ahrimjang.mail.common;

/**
 * Classification of an asynchronous bounce/complaint reported by a provider.
 *
 * <p>{@code HARD_BOUNCE} and {@code COMPLAINT} are permanent problems that
 * suppress the address; {@code SOFT_BOUNCE} is transient and retryable.
 */
public enum BounceType {
    HARD_BOUNCE,
    SOFT_BOUNCE,
    COMPLAINT,
}
