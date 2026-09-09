/* 직접 입력 수신자 파싱.

   붙여넣기·CSV 는 "이메일" 한 컬럼만 오지 않는다 — 이름 컬럼, 헤더 줄, `홍길동
   <a@b.com>` 표기가 섞인다. 그래서 구분자로 쪼개 전부 주소로 취급하면 이름이
   수신자로 잡히고, 화면의 "N명"과 실제 발송 대상이 어긋난다. 여기서는 줄 단위로
   보고 주소처럼 생긴 토큰만 거둔다. */

/** 한 줄에서 주소 후보를 찾는 패턴 — 구분자·꺾쇠·인용부호는 경계로 본다. */
const EMAIL_TOKEN = /[^\s,;<>"'()[\]]+@[^\s,;<>"'()[\]]+/g;

/**
 * 배달 가능한 형태인지 — mail-core 의 {@code EmailAddressValidator.SHAPE} 와 같은 규칙.
 * 도메인 품질(테스트 도메인·오타 후보) 판정은 서버가 소유하므로 여기서 흉내내지 않는다.
 */
const SHAPE = /^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$/;

export function isValidEmailShape(email: string): boolean {
  const trimmed = email.trim();
  if (trimmed.length > 254 || !SHAPE.test(trimmed)) {
    return false;
  }
  const local = trimmed.slice(0, trimmed.lastIndexOf("@"));
  return !local.startsWith(".") && !local.endsWith(".") && !local.includes("..") && local.length <= 64;
}

export interface ParsedRecipients {
  /** 중복 제거된 주소 (입력 순서 유지, 대소문자는 원본 그대로). */
  emails: string[];
  /** 형태가 깨져 발송할 수 없는 주소 — 서버가 거절하기 전에 화면에서 알린다. */
  invalid: string[];
  /** 비어 있지 않은데 주소를 하나도 못 찾은 줄 (헤더 줄·이름만 있는 줄). */
  unparsedLines: string[];
  /** 중복으로 걸러낸 개수. */
  duplicates: number;
}

/** 붙여넣은 텍스트·CSV 본문에서 수신자를 추려낸다. */
export function parseRecipients(raw: string): ParsedRecipients {
  const emails: string[] = [];
  const invalid: string[] = [];
  const unparsedLines: string[] = [];
  const seen = new Set<string>();
  let duplicates = 0;

  for (const line of raw.split(/\r?\n/)) {
    if (line.trim() === "") {
      continue;
    }
    const tokens = line.match(EMAIL_TOKEN);
    if (!tokens) {
      // 주소가 아예 없는 줄 — CSV 헤더이거나 이름만 적힌 줄
      unparsedLines.push(line.trim());
      continue;
    }
    for (const token of tokens) {
      const email = token.trim();
      const key = email.toLowerCase();
      if (seen.has(key)) {
        duplicates += 1;
        continue;
      }
      seen.add(key);
      if (isValidEmailShape(email)) {
        emails.push(email);
      } else {
        invalid.push(email);
      }
    }
  }
  return { emails, invalid, unparsedLines, duplicates };
}

/** 드롭·선택한 파일이 주소 목록으로 읽을 만한 텍스트인가. */
export function isRecipientFile(file: File): boolean {
  return /\.(csv|txt|tsv)$/i.test(file.name)
    || file.type.startsWith("text/")
    || file.type === "application/csv";
}
