package io.github.ahrimjang.mail.common;

/**
 * one send job = one queued message id
 */
public record SendJob(Long messageId) {
}
