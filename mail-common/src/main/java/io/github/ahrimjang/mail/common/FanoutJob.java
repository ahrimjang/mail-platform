package io.github.ahrimjang.mail.common;

/**
 * one fan-out job = one campaign whose recipient list the worker expands into send jobs
 */
public record FanoutJob(Long campaignId) {
}
