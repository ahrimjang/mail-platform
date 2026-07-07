import type { ReactNode } from "react";

/* Left brand panel shared by the login and signup split screens. */
export default function BrandPanel({ heading, checks }: { heading: ReactNode; checks: string[] }) {
  return (
    <div className="op-brandpanel">
      <div className="op-brand-row">
        <div className="op-logo-mark"><span className="op-logo-tri" /></div>
        <span className="op-brand-name">Outpace</span>
      </div>
      <h1>{heading}</h1>
      <div className="op-brand-checks">
        {checks.map((c) => (
          <div key={c} className="op-brand-check">
            <span className="tick"><span className="op-tickmark sm" /></span>
            {c}
          </div>
        ))}
      </div>
      <div className="op-brand-copyright">© 2026 Outpace</div>
    </div>
  );
}
