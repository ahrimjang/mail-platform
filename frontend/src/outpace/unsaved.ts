import { useEffect, useRef, useState } from "react";

/**
 * 저장하지 않은 편집 보호.
 *
 * <p>에디터·캠페인 작성 폼은 모두 수동 저장이다. 이 장치가 없으면 뒤로 한 번,
 * 새로고침 한 번에 수십 분의 작업이 아무 경고 없이 사라진다. 화면 밖 이탈(탭 닫기·
 * 새로고침·주소 직접 입력)은 브라우저 확인창으로, 화면 안 이탈(뒤로 버튼·메뉴 이동)은
 * {@link confirmLeave} 를 통과해야만 진행되게 한다.
 *
 * @param dirty 저장 시점과 지금이 다른가
 */
export function useUnsavedGuard(dirty: boolean): (message?: string) => boolean {
  useEffect(() => {
    if (!dirty) {
      return;
    }
    function onBeforeUnload(e: BeforeUnloadEvent) {
      // 브라우저는 문구를 무시하고 자체 확인창을 띄운다 — 값을 넣는 것 자체가 트리거다
      e.preventDefault();
      e.returnValue = "";
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  return function confirmLeave(
    message = "저장하지 않은 변경이 있어요. 이 화면을 벗어나면 사라집니다. 계속할까요?",
  ): boolean {
    return !dirty || window.confirm(message);
  };
}

/**
 * "저장 시점의 내용"과 지금을 비교하는 기준선.
 *
 * <p>내용을 문자열로 직렬화해 한 번 기록해 두고(기준선), 지금 값과 다르면 dirty 다.
 * 새 문서는 첫 렌더 직후, 편집은 불러온 직후를 기준으로 잡는다 — 아무것도 안 건드리고
 * 나가는 사용자에게 확인창을 띄우지 않기 위해서다.
 *
 * @param current 지금 내용의 직렬화 결과
 * @param ready   기준선을 잡아도 되는 시점인가(편집 모드는 로드 완료 후)
 */
export function useDirtyTracker(current: string, ready = true): {
  dirty: boolean;
  /** 저장 성공 시 호출 — 방금 저장한 내용을 새 기준선으로. */
  markSaved: (saved: string) => void;
} {
  const [baseline, setBaseline] = useState<string | null>(null);
  // current 는 매 렌더 바뀌므로 의존성에 넣지 않는다 — 기준선은 딱 한 번만 잡는다
  const latest = useRef(current);
  latest.current = current;

  useEffect(() => {
    if (baseline === null && ready) {
      setBaseline(latest.current);
    }
  }, [baseline, ready]);

  return {
    dirty: baseline !== null && current !== baseline,
    markSaved: setBaseline,
  };
}
