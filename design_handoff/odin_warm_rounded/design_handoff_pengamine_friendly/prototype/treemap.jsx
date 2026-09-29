// Treemap (squarified) — reusable, fits parent width
// Usage: <Treemap items={[{id,name,color,value,target?}]} height={240} showTargets={false} />

(function () {
  const { useRef, useState, useEffect, useMemo } = React;

  function squarify(items, rect) {
    // Sort by area desc
    const sorted = [...items].sort((a, b) => b.area - a.area);
    const result = [];
    let { x, y, w, h } = rect;

    function layoutRow(row, side, off, len) {
      const sum = row.reduce((s, r) => s + r.area, 0);
      if (sum <= 0 || side <= 0) {
        row.forEach(r => result.push({ ...r, rect: { x: 0, y: 0, w: 0, h: 0 } }));
        return;
      }
      const rowThick = sum / side;
      let cur = off;
      row.forEach(r => {
        const segLen = (r.area / sum) * side;
        let pos;
        if (len === w) {
          // horizontal row (rows stack vertically along h)
          pos = { x: x + cur, y: y, w: segLen, h: rowThick };
        } else {
          // vertical row (rows stack horizontally along w)
          pos = { x: x, y: y + cur, w: rowThick, h: segLen };
        }
        result.push({ ...r, rect: pos });
        cur += segLen;
      });
    }

    function worst(row, side) {
      if (side <= 0) return Infinity;
      const sum = row.reduce((s, r) => s + r.area, 0);
      const max = Math.max(...row.map(r => r.area));
      const min = Math.min(...row.map(r => r.area));
      const s2 = sum * sum;
      const side2 = side * side;
      return Math.max((side2 * max) / s2, s2 / (side2 * min));
    }

    let remaining = [...sorted];
    while (remaining.length > 0) {
      const side = Math.min(w, h);
      const row = [];
      let i = 0;
      while (i < remaining.length) {
        const trial = [...row, remaining[i]];
        if (row.length === 0 || worst(trial, side) <= worst(row, side)) {
          row.push(remaining[i]);
          i++;
        } else break;
      }
      const sum = row.reduce((s, r) => s + r.area, 0);
      if (w >= h) {
        const thick = sum / h;
        layoutRow(row, h, 0, w);
        x += thick;
        w -= thick;
      } else {
        const thick = sum / w;
        layoutRow(row, w, 0, h);
        y += thick;
        h -= thick;
      }
      remaining = remaining.slice(row.length);
    }
    return result;
  }

  function Treemap({ items, height = 240, onHover, hoveredId, selectedName, onClick, fmt, accent }) {
    const ref = useRef(null);
    const [w, setW] = useState(800);

    useEffect(() => {
      if (!ref.current) return;
      const ro = new ResizeObserver(entries => {
        for (const e of entries) setW(e.contentRect.width);
      });
      ro.observe(ref.current);
      return () => ro.disconnect();
    }, []);

    const filtered = items.filter(it => it.value > 0);
    const total = filtered.reduce((s, it) => s + it.value, 0);
    const area = w * height;
    const withArea = filtered.map(it => ({ ...it, area: total > 0 ? area * (it.value / total) : 0 }));
    const laid = useMemo(() => {
      if (w <= 0 || height <= 0 || withArea.length === 0) return [];
      return squarify(withArea, { x: 0, y: 0, w, h: height });
    }, [w, height, JSON.stringify(withArea.map(x => [x.id, x.value]))]);

    const _fmt = fmt || (n => Math.round(n).toLocaleString('nb-NO'));

    return (
      <div ref={ref} style={{ position: 'relative', width: '100%', height: height + 'px', overflow: 'hidden' }}>
        {laid.map(item => {
          const { x, y, w: iw, h: ih } = item.rect;
          const minDim = Math.min(iw, ih);
          const pct = total > 0 ? (item.value / total) * 100 : 0;
          const isFaded = hoveredId && hoveredId !== item.id || (selectedName && selectedName !== item.name);
          return (
            <div
              key={item.id}
              onMouseEnter={() => onHover && onHover(item.id)}
              onMouseLeave={() => onHover && onHover(null)}
              onClick={() => onClick && onClick(item)}
              title={`${item.name} — ${_fmt(item.value)} (${pct.toFixed(1)}%)`}
              style={{
                position: 'absolute',
                left: x, top: y, width: iw, height: ih,
                boxSizing: 'border-box',
                background: item.color,
                cursor: onClick ? 'pointer' : 'default',
                opacity: isFaded ? 0.35 : 1,
                transition: 'opacity 0.12s ease',
                overflow: 'hidden',
                outline: `1px solid ${accent || 'rgba(0,0,0,0.25)'}`,
                outlineOffset: '-1px',
              }}
            >
              {minDim > 22 && (
                <div style={{
                  position: 'absolute', inset: 0,
                  display: 'flex', flexDirection: 'column',
                  alignItems: 'flex-start', justifyContent: 'flex-end',
                  padding: '6px 8px',
                  pointerEvents: 'none',
                  color: '#fff',
                  textShadow: '0 1px 3px rgba(0,0,0,0.55)',
                  letterSpacing: '0.01em',
                }}>
                  <div style={{
                    fontSize: minDim > 80 ? 13 : (minDim > 50 ? 11 : 10),
                    fontWeight: 600,
                    textTransform: 'lowercase',
                    maxWidth: '100%',
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>{item.name}</div>
                  {minDim > 38 && (
                    <div style={{
                      fontFamily: 'var(--ff-mono)',
                      fontSize: minDim > 80 ? 11 : 9.5,
                      opacity: 0.95,
                      display: 'flex', gap: 8, alignItems: 'baseline',
                    }}>
                      <span>{pct.toFixed(1)}%</span>
                      {minDim > 90 && <span style={{ opacity: 0.75 }}>{_fmt(item.value)}</span>}
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    );
  }

  window.Treemap = Treemap;
})();
