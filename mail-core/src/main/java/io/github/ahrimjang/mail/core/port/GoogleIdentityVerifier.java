package io.github.ahrimjang.mail.core.port;

/**
 * 구글 ID 토큰 검증 포트. 프론트의 Google Identity Services 버튼이 넘겨준
 * ID 토큰(JWT)이 진짜 구글 발급이고 우리 클라이언트 대상(aud)인지 확인한다.
 * 신원 확인만 여기서 하고, 세션(자체 JWT) 발급은 기존 TokenService 가 담당.
 */
public interface GoogleIdentityVerifier {

    /**
     * @return 검증된 구글 신원
     * @throws IllegalArgumentException 위조·만료·대상 불일치 토큰
     * @throws IllegalStateException    구글 로그인이 설정되지 않은 배포(클라이언트 ID 미설정)
     */
    GoogleIdentity verify(String idToken);

    /**
     * @param subject       구글이 발급한 불변 사용자 식별자 (sub 클레임)
     * @param emailVerified 구글이 이 메일함 소유를 검증했는지 — true 면 우리 인증 메일 생략
     */
    record GoogleIdentity(String subject, String email, String displayName, boolean emailVerified) {
    }
}
