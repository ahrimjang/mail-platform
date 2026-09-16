/* 페이지별 메타 태그 — 라우터 한 곳(App)에서 경로를 보고 문서 head 를 갱신한다.
 *
 * SPA 라 서버가 경로별 HTML 을 따로 주지 않는다. 구글은 JS 를 실행하고 크롤링하므로
 * 여기서 바꾼 값을 읽지만, 네이버·카카오·페이스북의 미리보기 수집기는 JS 를 돌리지
 * 않고 index.html 의 정적 태그만 본다 — 그래서 index.html 에 서비스 기본값(랜딩 기준)을
 * 같이 박아 두고, 이 훅은 그 위에 경로별 값을 덮어쓴다. 링크 미리보기가 어떤 경로든
 * 랜딩 문구로 나오는 것은 의도된 하한선이다.
 *
 * 콘솔 화면은 로그인해야 보이는 데다 색인될 이유가 없어 noindex 로 둔다
 * (public/robots.txt 의 Disallow 와 짝).
 */
import { useEffect } from "react";
import { useLocation } from "react-router-dom";

export const SITE_URL = "https://outpacemail.com";
const SITE_NAME = "Outpace";
const OG_IMAGE = SITE_URL + "/og-image.png";

/** 검색 결과에 노출할 공개 페이지. 여기 없는 경로는 전부 noindex. */
const PUBLIC_PAGES: Record<string, { title: string; description: string }> = {
  "/": {
    title: "Outpace — 구독자 수가 아니라 보낸 만큼만, 이메일 발송 서비스",
    description:
      "구독자 수가 아니라 월 발송량으로 요금을 매기는 이메일 발송 서비스. 드래그앤드롭 에디터, 예약 발송, A/B 테스트, 오픈·클릭 분석까지. 카드 등록 없이 월 1,000통 무료.",
  },
  "/pricing": {
    title: "요금제 — 보낸 만큼만 내는 이메일 발송 | Outpace",
    description:
      "무료 스타터(월 1,000통)부터 스탠다드 9,900원, 프로 29,000원까지. 구독자 수가 아니라 월 발송량 기준이라 안 보낸 달에는 요금이 오르지 않습니다.",
  },
  "/guide": {
    title: "사용 가이드 — 첫 뉴스레터 보내기 | Outpace",
    description:
      "연락처 가져오기부터 이메일 작성, 예약 발송, 오픈·클릭 확인까지 순서대로 안내합니다. 수신거부와 반송 처리는 자동입니다.",
  },
  "/developers": {
    title: "개발자 가이드 — 구독 API 연동 | Outpace",
    description:
      "API 키 한 개로 내 사이트의 구독 폼을 Outpace 리스트에 연결합니다. 요청 예시와 응답 형식, 오류 코드를 한 페이지에 정리했습니다.",
  },
  "/signup": {
    title: "무료로 시작하기 | Outpace",
    description: "카드 등록 없이 가입하고 바로 월 1,000통을 보내보세요.",
  },
  "/privacy": {
    title: "개인정보처리방침 | Outpace",
    description: "Outpace 가 수집하는 정보와 이용·보관·파기 기준을 안내합니다.",
  },
  "/terms": {
    title: "이용약관 | Outpace",
    description: "Outpace 서비스 이용약관입니다.",
  },
};

/** name= 과 property= 를 같은 방식으로 다룬다(OG 는 property, 나머지는 name). */
function setMeta(attr: "name" | "property", key: string, content: string) {
  const selector = `meta[${attr}="${key}"]`;
  let tag = document.head.querySelector<HTMLMetaElement>(selector);
  if (!tag) {
    tag = document.createElement("meta");
    tag.setAttribute(attr, key);
    document.head.appendChild(tag);
  }
  tag.setAttribute("content", content);
}

function setCanonical(url: string) {
  let link = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
  if (!link) {
    link = document.createElement("link");
    link.rel = "canonical";
    document.head.appendChild(link);
  }
  link.href = url;
}

export function useSeo() {
  const { pathname } = useLocation();
  useEffect(() => {
    const page = PUBLIC_PAGES[pathname];
    const title = page ? page.title : `${SITE_NAME} 콘솔`;
    const description = page
      ? page.description
      : "Outpace 콘솔 — 캠페인 발송과 수신자 관리를 한곳에서.";
    // 콘솔·인증 화면은 색인 대상이 아니다. 로그인 링크가 검색 결과에 뜨면
    // 방문자가 소개 없이 로그인 폼부터 만난다.
    const robots = page ? "index,follow" : "noindex,nofollow";
    const canonical = SITE_URL + (pathname === "/" ? "/" : pathname);

    document.title = title;
    setMeta("name", "description", description);
    setMeta("name", "robots", robots);
    setCanonical(canonical);

    setMeta("property", "og:type", "website");
    setMeta("property", "og:site_name", SITE_NAME);
    setMeta("property", "og:locale", "ko_KR");
    setMeta("property", "og:title", title);
    setMeta("property", "og:description", description);
    setMeta("property", "og:url", canonical);
    setMeta("property", "og:image", OG_IMAGE);

    setMeta("name", "twitter:card", "summary_large_image");
    setMeta("name", "twitter:title", title);
    setMeta("name", "twitter:description", description);
    setMeta("name", "twitter:image", OG_IMAGE);
  }, [pathname]);
}
