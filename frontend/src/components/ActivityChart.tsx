import type { DashboardDay } from "../types";
import { fmt } from "../outpace/format";

/* Dependency-free daily activity chart: sent as bars, opens/clicks as lines.
   Shared by the dashboard and the analytics page. */
export default function ActivityChart({ daily }: { daily: DashboardDay[] }) {
  const W = 720;
  const H = 190;
  const PAD_L = 44;   // room for y labels
  const PAD_B = 22;   // room for x labels
  const PAD_T = 10;

  const n = daily.length;
  const innerW = W - PAD_L - 8;
  const innerH = H - PAD_T - PAD_B;
  const slot = innerW / Math.max(n, 1);

  const rawMax = Math.max(1, ...daily.map((d) => d.sent));
  // Snap the axis top to a round number so gridline labels stay readable.
  const mag = Math.pow(10, Math.floor(Math.log10(rawMax)));
  const yMax = Math.ceil(rawMax / mag) * mag;

  const x = (i: number) => PAD_L + i * slot + slot / 2;
  const y = (v: number) => PAD_T + innerH * (1 - v / yMax);

  const line = (pick: (d: DashboardDay) => number) =>
    daily.map((d, i) => `${x(i)},${y(pick(d))}`).join(" ");

  const labelEvery = Math.max(1, Math.ceil(n / 7));
  const dayLabel = (iso: string) => {
    const d = new Date(`${iso}T00:00:00`);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  };

  return (
    <svg className="op-chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="최근 발송 추이">
      {/* horizontal gridlines: 0 / 50% / 100% */}
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line x1={PAD_L} x2={W - 8} y1={y(yMax * f)} y2={y(yMax * f)} className="grid" />
          <text x={PAD_L - 8} y={y(yMax * f) + 4} className="ylabel">{fmt(Math.round(yMax * f))}</text>
        </g>
      ))}

      {daily.map((d, i) => {
        const bw = Math.min(26, slot * 0.5);
        return (
          <g key={d.date}>
            <rect
              className="bar"
              x={x(i) - bw / 2}
              y={y(d.sent)}
              width={bw}
              height={Math.max(0, PAD_T + innerH - y(d.sent))}
              rx={3}
            >
              <title>{`${d.date} · 발송 ${fmt(d.sent)} · 오픈 ${fmt(d.opened)} · 클릭 ${fmt(d.clicked)}${d.failed > 0 ? ` · 실패 ${fmt(d.failed)}` : ""}`}</title>
            </rect>
            {i % labelEvery === 0 && (
              <text x={x(i)} y={H - 6} className="xlabel">{dayLabel(d.date)}</text>
            )}
          </g>
        );
      })}

      <polyline className="line open" points={line((d) => d.opened)} />
      <polyline className="line click" points={line((d) => d.clicked)} />
      {daily.map((d, i) => (
        <g key={`dots-${d.date}`}>
          <circle className="dot open" cx={x(i)} cy={y(d.opened)} r={2.6} />
          <circle className="dot click" cx={x(i)} cy={y(d.clicked)} r={2.6} />
        </g>
      ))}
    </svg>
  );
}
