package io.github.ahrimjang.mail.common;

/**
 * 캠페인 등록이 막힐 수 있는 조건을 <b>작성 전에</b> 알려주는 상태.
 *
 * <p>등록 게이트(이메일 인증 → 평판 정지 → 발신 도메인 → 워밍업 → 플랜 한도)는
 * 모두 마지막 클릭에서 예외로 터진다. 리스트 500명을 고르고 확인 모달까지 통과한 뒤에
 * "첫 발송은 50명까지"를 처음 듣는 식이다. 이 뷰는 같은 판정 재료를 읽기 전용으로
 * 내보내 작성 화면이 미리 보여줄 수 있게 한다. 집행은 여전히 등록 시점의 게이트가
 * 담당한다 — 여기 값은 안내용이고 신뢰 경계가 아니다.
 *
 * @param emailVerified       가입 이메일 인증 완료 여부 (false 면 어떤 발송도 안 된다)
 * @param suspended           평판 자동 정지 상태인가
 * @param suspensionReason    정지 사유 (정지 아닐 때 null)
 * @param senderDomain        허용된 발신 도메인 — null/빈 값이면 제한 없음(로컬 기본)
 * @param warmupActive        워밍업 중인가 (누적 발송이 임계 미만)
 * @param warmupBatchLimit    워밍업 중 캠페인 1건당 최대 수신자 (워밍업이 아니면 null)
 * @param warmupSentRemaining 워밍업 졸업까지 남은 누적 발송량 (워밍업이 아니면 null)
 * @param plan                현재 플랜
 * @param monthlySendLimit    월 발송 한도 — null = 무제한
 * @param monthlySent         이번 달 발송 성공 수
 */
public record SendingPreflightView(
        boolean emailVerified,
        boolean suspended,
        String suspensionReason,
        String senderDomain,
        boolean warmupActive,
        Integer warmupBatchLimit,
        Long warmupSentRemaining,
        String plan,
        Long monthlySendLimit,
        long monthlySent
) {
}
