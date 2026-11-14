package com.gaoyc.studio.gen;

import com.gaoyc.studio.domain.AppSpec;

/** 所有产物共用的样式表：CSS 变量 + 响应式 + 深色模式。不含任何外部依赖。 */
final class CssLibrary {

    private CssLibrary() {
    }

    static String styles(AppSpec spec) {
        return """
                :root {
                  --accent: ${accent};
                  --accent-soft: color-mix(in srgb, ${accent} 14%, white);
                  --bg: #f7f8fa;
                  --surface: #ffffff;
                  --text: #1b1d21;
                  --muted: #6b7280;
                  --border: #e5e7eb;
                  --radius: 12px;
                  --shadow: 0 1px 2px rgba(16, 24, 40, .06), 0 8px 24px rgba(16, 24, 40, .06);
                }
                [data-theme="dark"] {
                  --bg: #101216;
                  --surface: #181b21;
                  --text: #eef1f6;
                  --muted: #9aa4b2;
                  --border: #272c35;
                  --accent-soft: #1d2733;
                  --shadow: 0 1px 2px rgba(0, 0, 0, .5);
                }
                * { box-sizing: border-box; }
                html, body { margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
                  background: var(--bg);
                  color: var(--text);
                  line-height: 1.6;
                  -webkit-font-smoothing: antialiased;
                }
                .wrap { max-width: 880px; margin: 0 auto; padding: 32px 20px 64px; }
                .app-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
                h1 { font-size: 26px; margin: 0 0 4px; letter-spacing: -0.01em; }
                .subtitle { margin: 0; color: var(--muted); font-size: 14px; }
                .card {
                  background: var(--surface);
                  border: 1px solid var(--border);
                  border-radius: var(--radius);
                  box-shadow: var(--shadow);
                  padding: 20px;
                }
                .add-row { display: flex; gap: 10px; margin: 24px 0 16px; }
                input[type="text"], input[type="email"], textarea, select {
                  flex: 1;
                  padding: 11px 13px;
                  font-size: 14px;
                  font-family: inherit;
                  color: var(--text);
                  background: var(--surface);
                  border: 1px solid var(--border);
                  border-radius: 9px;
                  outline: none;
                }
                input:focus, textarea:focus, select:focus {
                  border-color: var(--accent);
                  box-shadow: 0 0 0 3px var(--accent-soft);
                }
                button {
                  font-family: inherit;
                  font-size: 14px;
                  cursor: pointer;
                  border-radius: 9px;
                  border: 1px solid transparent;
                  padding: 10px 16px;
                  background: var(--accent);
                  color: #fff;
                  transition: filter .15s ease, transform .05s ease;
                }
                button:hover { filter: brightness(1.06); }
                button:active { transform: translateY(1px); }
                button:disabled { opacity: .5; cursor: not-allowed; }
                .icon-btn {
                  background: transparent;
                  color: var(--text);
                  border: 1px solid var(--border);
                  padding: 8px 11px;
                  line-height: 1;
                }
                .filters { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
                .filter {
                  background: transparent;
                  color: var(--muted);
                  border: 1px solid var(--border);
                  padding: 7px 14px;
                  font-size: 13px;
                }
                .filter.is-active { background: var(--accent); color: #fff; border-color: var(--accent); }
                .task-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
                .task {
                  display: flex;
                  align-items: center;
                  gap: 12px;
                  padding: 12px 14px;
                  background: var(--surface);
                  border: 1px solid var(--border);
                  border-radius: 10px;
                }
                .task.is-done .task-text { text-decoration: line-through; color: var(--muted); }
                .task-text { flex: 1; word-break: break-word; }
                .task-meta { font-size: 12px; color: var(--muted); }
                .delete {
                  background: transparent;
                  color: var(--muted);
                  border: 1px solid var(--border);
                  padding: 4px 9px;
                  font-size: 13px;
                }
                .delete:hover { color: #d33; border-color: #f3c2c2; }
                .empty { text-align: center; color: var(--muted); padding: 28px 0; font-size: 14px; }
                .app-footer { margin-top: 18px; color: var(--muted); font-size: 13px; display: flex; gap: 8px; flex-wrap: wrap; }
                .grid { display: grid; gap: 14px; }
                .grid-3 { grid-template-columns: repeat(3, 1fr); }
                .grid-2 { grid-template-columns: repeat(2, 1fr); }
                .kpi { display: flex; flex-direction: column; gap: 6px; }
                .kpi-label { font-size: 13px; color: var(--muted); }
                .kpi-value { font-size: 28px; font-weight: 650; letter-spacing: -0.02em; }
                .kpi-delta { font-size: 12px; color: #0f9d8f; }
                .kpi-delta.is-down { color: #d9467a; }
                .section-title { font-size: 15px; font-weight: 650; margin: 0 0 12px; }
                .hero { text-align: center; padding: 56px 20px 40px; }
                .hero h1 { font-size: 38px; line-height: 1.2; margin-bottom: 14px; }
                .hero p { color: var(--muted); font-size: 17px; max-width: 560px; margin: 0 auto 26px; }
                .cta-row { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }
                .btn-ghost { background: transparent; color: var(--text); border: 1px solid var(--border); }
                .feature { display: flex; flex-direction: column; gap: 8px; }
                .feature-icon { font-size: 22px; }
                .feature h3 { margin: 0; font-size: 15px; }
                .feature p { margin: 0; color: var(--muted); font-size: 13.5px; }
                .field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 14px; }
                .field label { font-size: 13px; color: var(--muted); }
                .field .error { color: #d33; font-size: 12px; min-height: 16px; }
                .notice { padding: 12px 14px; border-radius: 10px; background: var(--accent-soft); font-size: 13.5px; }
                .price-card { display: flex; flex-direction: column; gap: 10px; }
                .price-card.is-featured { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent), var(--shadow); }
                .price { font-size: 30px; font-weight: 700; letter-spacing: -0.02em; }
                .price span { font-size: 13px; font-weight: 400; color: var(--muted); }
                .plan-list { margin: 0; padding-left: 18px; color: var(--muted); font-size: 13.5px; }
                .post { display: flex; flex-direction: column; gap: 6px; padding: 16px 0; border-bottom: 1px solid var(--border); cursor: pointer; }
                .post:last-child { border-bottom: 0; }
                .post h3 { margin: 0; font-size: 16px; }
                .post p { margin: 0; color: var(--muted); font-size: 13.5px; }
                canvas { width: 100%; height: 220px; display: block; }
                .sr-only {
                  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
                  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
                }
                @media (max-width: 640px) {
                  .wrap { padding: 22px 14px 48px; }
                  .grid-3, .grid-2 { grid-template-columns: 1fr; }
                  .hero h1 { font-size: 28px; }
                  .hero { padding: 32px 8px 24px; }
                }
                @media (prefers-reduced-motion: reduce) {
                  * { transition: none !important; animation: none !important; }
                }
                """.replace("${accent}", spec.accent());
    }
}
