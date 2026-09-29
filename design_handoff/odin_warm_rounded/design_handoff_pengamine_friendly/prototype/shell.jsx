// Shared components, hooks, and utilities for Odin

(function () {
  const { useState, useEffect, useRef, useMemo, useCallback } = React;

  // ---------------- Format helpers ----------------
  const nf0 = new Intl.NumberFormat('nb-NO', { maximumFractionDigits: 0 });
  const nf2 = new Intl.NumberFormat('nb-NO', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  function fmtKr(n, decimals = 0) {
    if (n == null || isNaN(n)) return '—';
    const f = decimals === 2 ? nf2 : nf0;
    return f.format(n);
  }
  function fmtSigned(n) {
    if (n == null || isNaN(n)) return '—';
    const s = nf0.format(Math.abs(n));
    if (n > 0) return '+' + s;
    if (n < 0) return '−' + s;
    return s;
  }
  function fmtPct(n, digits = 1) {
    if (n == null || isNaN(n)) return '—';
    return n.toFixed(digits) + '%';
  }

  // ---------------- Period helpers ----------------
  const MONTHS_NO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Des'];

  function periodLabel(period) {
    if (!period) return '';
    if (period.type === 'month') return `${MONTHS_NO[period.month - 1]} ${period.year}`;
    if (period.type === 'quarter') return `Q${period.quarter} ${period.year}`;
    if (period.type === 'year') return `${period.year}`;
    return '';
  }

  function inPeriod(date, period) {
    const [d, m, y] = date.split('.').map(Number);
    if (period.type === 'month') return y === period.year && m === period.month;
    if (period.type === 'quarter') return y === period.year && Math.ceil(m / 3) === period.quarter;
    if (period.type === 'year') return y === period.year;
    return false;
  }

  // ---------------- Icon (using svg, kept minimal) ----------------
  function Icon({ name, size = 14, stroke = 1.5 }) {
    const props = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: stroke, strokeLinecap: 'round', strokeLinejoin: 'round' };
    switch (name) {
      case 'caret-down':
        return <svg {...props}><path d="M6 9l6 6 6-6"/></svg>;
      case 'caret-right':
        return <svg {...props}><path d="M9 6l6 6-6 6"/></svg>;
      case 'caret-up':
        return <svg {...props}><path d="M6 15l6-6 6 6"/></svg>;
      case 'plus':
        return <svg {...props}><path d="M12 5v14M5 12h14"/></svg>;
      case 'close':
        return <svg {...props}><path d="M18 6L6 18M6 6l12 12"/></svg>;
      case 'sun':
        return <svg {...props}><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>;
      case 'moon':
        return <svg {...props}><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>;
      case 'search':
        return <svg {...props}><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.35-4.35"/></svg>;
      case 'check':
        return <svg {...props}><path d="M20 6L9 17l-5-5"/></svg>;
      case 'dot':
        return <svg {...props} fill="currentColor" stroke="none"><circle cx="12" cy="12" r="3"/></svg>;
      case 'arrow-up':
        return <svg {...props}><path d="M12 19V5M5 12l7-7 7 7"/></svg>;
      case 'arrow-down':
        return <svg {...props}><path d="M12 5v14M5 12l7 7 7-7"/></svg>;
      case 'arrow-right':
        return <svg {...props}><path d="M5 12h14M12 5l7 7-7 7"/></svg>;
      case 'menu':
        return <svg {...props}><path d="M3 12h18M3 6h18M3 18h18"/></svg>;
      case 'settings':
        return <svg {...props}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>;
      case 'trend-up':
        return <svg {...props}><path d="M23 6l-9.5 9.5-5-5L1 18"/><path d="M17 6h6v6"/></svg>;
      case 'wallet':
        return <svg {...props}><rect x="2" y="6" width="20" height="14" rx="2"/><path d="M16 13h2"/></svg>;
      case 'pie':
        return <svg {...props}><path d="M21.21 15.89A10 10 0 1 1 8 2.83"/><path d="M22 12A10 10 0 0 0 12 2v10z"/></svg>;
      case 'list':
        return <svg {...props}><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>;
      case 'bars':
        return <svg {...props}><path d="M12 20V10M18 20V4M6 20v-6"/></svg>;
      case 'sigma':
        return <svg {...props}><path d="M4 4h16l-9 8 9 8H4"/></svg>;
      case 'filter':
        return <svg {...props}><path d="M22 3H2l8 9.46V19l4 2v-8.54L22 3z"/></svg>;
      case 'tag':
        return <svg {...props}><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg>;
      case 'card':
        return <svg {...props}><rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/></svg>;
      case 'edit':
        return <svg {...props}><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>;
      case 'trash':
        return <svg {...props}><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-2 14H7L5 6"/></svg>;
      case 'eye':
        return <svg {...props}><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>;
      default:
        return null;
    }
  }

  // ---------------- Theme toggle ----------------
  function useTheme() {
    const [theme, setTheme] = useState(() => localStorage.getItem('odin-theme') || 'dark');
    useEffect(() => {
      document.documentElement.dataset.theme = theme;
      localStorage.setItem('odin-theme', theme);
    }, [theme]);
    return [theme, setTheme];
  }

  // ---------------- Sparkline ----------------
  function Sparkline({ values, width = 80, height = 22, color = 'currentColor', accent }) {
    if (!values || values.length < 2) return null;
    const min = Math.min(...values);
    const max = Math.max(...values);
    const range = max - min || 1;
    const stepX = width / (values.length - 1);
    const pts = values.map((v, i) => [i * stepX, height - ((v - min) / range) * height]);
    const path = pts.map((p, i) => (i === 0 ? `M${p[0]},${p[1]}` : `L${p[0]},${p[1]}`)).join(' ');
    const lastUp = values[values.length - 1] >= values[values.length - 2];
    return (
      <svg width={width} height={height} style={{ display: 'block', overflow: 'visible' }}>
        <path d={path} fill="none" stroke={color} strokeWidth="1.25" />
        <circle cx={pts[pts.length - 1][0]} cy={pts[pts.length - 1][1]} r="2"
                fill={lastUp ? (accent && accent.up) : (accent && accent.down)} />
      </svg>
    );
  }

  // ---------------- Tabs (segmented control) ----------------
  function Segmented({ options, value, onChange, size = 'sm' }) {
    const pad = size === 'xs' ? '3px 8px' : '5px 12px';
    const fs = size === 'xs' ? 11 : 12;
    return (
      <div className="seg">
        {options.map(opt => (
          <button
            key={opt.value}
            className={'seg-btn' + (opt.value === value ? ' is-active' : '')}
            onClick={() => onChange(opt.value)}
            style={{ padding: pad, fontSize: fs }}
          >
            {opt.label}
          </button>
        ))}
      </div>
    );
  }

  // ---------------- StatusTile (key metrics in header) ----------------
  function StatusTile({ label, value, sub, accent, mono = true, delta, trend }) {
    return (
      <div className="status-tile">
        <div className="status-label">{label}</div>
        <div className="status-row">
          <span className={'status-value' + (mono ? ' mono' : '')} style={accent ? { color: accent } : null}>
            {value}
          </span>
          {trend && <span style={{ marginLeft: 8 }}>{trend}</span>}
        </div>
        {(sub || delta != null) && (
          <div className="status-sub">
            {delta != null && (
              <span className={'mono delta ' + (delta >= 0 ? 'up' : 'down')}>
                {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)}%
              </span>
            )}
            {sub && <span style={{ marginLeft: delta != null ? 8 : 0, opacity: 0.7 }}>{sub}</span>}
          </div>
        )}
      </div>
    );
  }

  // ---------------- Period selector v2 ----------------
  function PeriodSelector({ period, onChange }) {
    return (
      <div className="period-sel">
        <button className="period-year" onClick={() => onChange({ ...period, type: 'year' })}>
          {period.year}
        </button>
        <div className="period-months">
          {MONTHS_NO.map((m, i) => {
            const active = period.type === 'month' && period.month === i + 1;
            return (
              <button
                key={m}
                className={'period-month' + (active ? ' is-active' : '')}
                onClick={() => onChange({ ...period, type: 'month', month: i + 1 })}
              >
                {m}
              </button>
            );
          })}
        </div>
        <div className="period-types">
          <button className={'period-type' + (period.type === 'month' ? ' is-active' : '')}
                  onClick={() => onChange({ ...period, type: 'month' })}>Måned</button>
          <button className={'period-type' + (period.type === 'quarter' ? ' is-active' : '')}
                  onClick={() => onChange({ ...period, type: 'quarter', quarter: Math.ceil((period.month || 5) / 3) })}>Kvartal</button>
          <button className={'period-type' + (period.type === 'year' ? ' is-active' : '')}
                  onClick={() => onChange({ ...period, type: 'year' })}>År</button>
        </div>
      </div>
    );
  }

  Object.assign(window, {
    fmtKr, fmtSigned, fmtPct, MONTHS_NO,
    periodLabel, inPeriod,
    Icon, useTheme, Sparkline, Segmented, StatusTile, PeriodSelector,
  });
})();
