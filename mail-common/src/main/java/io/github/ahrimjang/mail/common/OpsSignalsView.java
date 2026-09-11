package io.github.ahrimjang.mail.common;

import java.util.List;

/**
 * 운영 신호 패널 — Grafana 를 열지 않고도 "지금 뭔가 이상한가"를 한눈에.
 *
 * @param workspaces          전체 워크스페이스 수
 * @param signups7d           최근 7일 가입
 * @param suspended           현재 발송 정지 중인 워크스페이스 수
 * @param inFlight            지금 EXPANDING/SENDING 인 캠페인 수
 * @param failed24h           최근 24시간 FAILED 로 끝난 메시지 수(DLQ 뒷정리 포함)
 * @param bounced24h          최근 24시간 BOUNCED
 * @param sent24h             최근 24시간 SENT
 * @param aborted24h          최근 24시간 발송 중 중단된 캠페인 수
 * @param inFlightCampaigns   진행 중 캠페인 목록(오래 머무는 순) — 고착 의심의 1차 화면
 * @param suspendedWorkspaces 정지 중인 워크스페이스 목록
 */
public record OpsSignalsView(
        long workspaces,
        long signups7d,
        long suspended,
        long inFlight,
        long failed24h,
        long bounced24h,
        long sent24h,
        long aborted24h,
        List<OpsCampaignRow> inFlightCampaigns,
        List<OpsWorkspaceRow> suspendedWorkspaces
) {
}
