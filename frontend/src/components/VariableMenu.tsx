import { useEffect, useRef, useState } from "react";

/* The variables dispatch actually fills: the recipient contact's well-known
   fields (Contact.toVariables). Ad-hoc sends only get {{email}}. */
const VARIABLES = [
  { token: "{{firstName}}", label: "이름" },
  { token: "{{lastName}}", label: "성" },
  { token: "{{email}}", label: "이메일 주소" },
];

/**
 * "＋ 개인화 변수" chip + dropdown, shared by every editor and the compose
 * form. The caller inserts the picked token wherever its editor's caret is.
 * Mousedown is swallowed so a contentEditable selection survives the click
 * (same trick as the rich-text toolbar).
 */
export default function VariableMenu({ onInsert, buttonClass }: {
  onInsert: (token: string) => void;
  buttonClass?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  return (
    <span className="op-varmenu" ref={ref} onMouseDown={(e) => e.preventDefault()}>
      <span className={buttonClass ?? "op-tt wide varbtn"} onClick={() => setOpen((o) => !o)}>
        ＋ 개인화 변수
      </span>
      {open && (
        <div className="op-varmenu-pop">
          {VARIABLES.map((v) => (
            <button key={v.token} onClick={() => { onInsert(v.token); setOpen(false); }}>
              <code>{v.token}</code>
              <span>{v.label}</span>
            </button>
          ))}
          <div className="note">
            CSV로 가져온 사용자 속성도 {"{{속성명}}"}으로 쓸 수 있어요.
            발송 시 수신자별 값으로 바뀌고, 값이 없으면 빈칸이 됩니다.
          </div>
        </div>
      )}
    </span>
  );
}
