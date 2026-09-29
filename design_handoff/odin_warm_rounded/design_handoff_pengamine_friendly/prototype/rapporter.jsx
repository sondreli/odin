// Rapporter (Reports) page

(function () {
  const { useState, useMemo, useRef, useEffect } = React;

  function BarChart({ data, height = 240, color }) {
    const max = Math.max(0, ...data.map(d => d.value));
    const min = Math.min(0, ...data.map(d => d.value));
    const range = max - min || 1;
    const zeroY = (max / range) * height;
    return (
      <div className="rep-chart" style={{ height }}>
        <div className="rep-bars">
          {data.map((d, i) => {
            const isNeg = d.value < 0;
            const barH = (Math.abs(d.value) / range) * height;
            return (
              <div key={i} className="rep-bar-col">
                <div className="rep-bar-track" style={{ height }}>
                  <div className="rep-bar-fill"
                       style={{
                         position: 'absolute',
                         left: '10%', right: '10%',
                         bottom: isNeg ? (zeroY - barH) : zeroY,
                         height: barH,
                         background: isNeg ? 'var(--c-down)' : (color || 'var(--c-up)'),
                       }}/>
                  <div className="rep-bar-zero" style={{ bottom: zeroY }}/>
                </div>
                <div className="rep-bar-label mono dim">{d.label}</div>
                <div className="rep-bar-value mono">{fmtKr(d.value)}</div>
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  function WaterfallChart({ data, height = 240 }) {
    // Simple waterfall: each bar starts where previous ended
    let cum = 0;
    const bars = data.map(d => {
      const start = cum;
      cum += d.value;
      return { ...d, start, end: cum };
    });
    const min = Math.min(0, ...bars.map(b => Math.min(b.start, b.end)));
    const max = Math.max(0, ...bars.map(b => Math.max(b.start, b.end)));
    const range = max - min || 1;
    return (
      <div className="rep-chart" style={{ height }}>
        <div className="rep-bars">
          {bars.map((b, i) => {
            const top = ((max - Math.max(b.start, b.end)) / range) * height;
            const h = (Math.abs(b.value) / range) * height;
            return (
              <div key={i} className="rep-bar-col">
                <div className="rep-bar-track" style={{ height }}>
                  <div className="rep-bar-fill"
                       style={{
                         position: 'absolute',
                         left: '10%', right: '10%',
                         top, height: h,
                         background: b.value < 0 ? 'var(--c-down)' : 'var(--c-up)',
                       }}/>
                </div>
                <div className="rep-bar-label mono dim">{b.label}</div>
                <div className="rep-bar-value mono">{fmtSigned(b.value)}</div>
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  function ReportCard({ report, onClick }) {
    // Generate data points for last 5 months
    const data = useMemo(() => {
      return MONTHLY_HISTORY.map(m => {
        let value = 0;
        if (report.expression === 'alle-inntekter - alle-utgifter') {
          value = m.income + m.expense;
        } else if (report.expression === 'bolig + faste utgifter') {
          value = (m.bolig || 0) + (m.faste || 0);
        } else if (report.expression === 'mat + mat ute') {
          value = (m.mat || 0) + Math.round((m.mat || 0) * 0.18);
        } else if (report.expression === 'ting + aktivitet + hytte + ferie') {
          value = (m.ting || 0) - 1200 - 800;
        }
        return { label: m.label, value };
      });
    }, [report]);

    const sum = data.reduce((s, d) => s + d.value, 0);
    const avg = sum / data.length;

    return (
      <div className="report-card" onClick={onClick}>
        <div className="report-card-head">
          <div className="report-name">{report.name}</div>
          <div className="report-expr mono dim">{report.expression}</div>
        </div>
        <div className="report-mini">
          {report.chartType === 'waterfall' ? (
            <WaterfallChart data={data} height={120}/>
          ) : (
            <BarChart data={data} height={120}/>
          )}
        </div>
        <div className="report-card-stats">
          <div>
            <div className="status-label">Snitt/mnd</div>
            <div className={'mono med ' + (avg >= 0 ? 'pos' : 'neg')}>{fmtSigned(avg)}</div>
          </div>
          <div>
            <div className="status-label">Sum</div>
            <div className="mono med">{fmtSigned(sum)}</div>
          </div>
        </div>
      </div>
    );
  }

  function RapporterPage({ period, onChangePeriod }) {
    const [openReport, setOpenReport] = useState(null);
    const [expression, setExpression] = useState('');
    const [chartType, setChartType] = useState('bar');

    const data = useMemo(() => {
      if (!expression) return [];
      return MONTHLY_HISTORY.map(m => {
        let value = 0;
        if (expression === 'alle-inntekter - alle-utgifter') {
          value = m.income + m.expense;
        } else if (expression === 'bolig + faste utgifter') {
          value = (m.bolig || 0) + (m.faste || 0);
        } else if (expression === 'mat + mat ute') {
          value = (m.mat || 0) - 1200;
        } else if (expression === 'ting + aktivitet + hytte + ferie') {
          value = (m.ting || 0) - 1800;
        } else if (expression === 'alle-inntekter') {
          value = m.income;
        } else if (expression === 'alle-utgifter') {
          value = m.expense;
        } else {
          value = m.expense + m.income;
        }
        return { label: m.label, value };
      });
    }, [expression]);

    const variables = ['alle-inntekter', 'alle-utgifter', ...CATEGORIES.map(c => c.name), ...TAGS.map(t => 'tag:' + t.name)];
    const operators = ['+', '-', '*', '/', '(', ')'];

    function openR(r) {
      setOpenReport(r);
      setExpression(r.expression);
      setChartType(r.chartType);
    }
    function closeR() { setOpenReport(null); }
    function newR() {
      const r = { id: 'new', name: 'Ny rapport', expression: 'alle-inntekter - alle-utgifter', chartType: 'bar' };
      openR(r);
    }

    // ----- Gallery view -----
    if (!openReport) {
      return (
        <div className="page rapporter">
          <div className="page-header">
            <h2>Rapporter</h2>
            <PeriodSelector period={period} onChange={onChangePeriod}/>
            <button className="btn-primary" style={{ marginLeft: 'auto' }} onClick={newR}>
              <Icon name="plus" size={12}/> Ny rapport
            </button>
          </div>
          <div className="report-gallery">
            {REPORTS.map(r => (
              <ReportCard key={r.id} report={r} onClick={() => openR(r)}/>
            ))}
            <button className="report-card report-new" onClick={newR}>
              <Icon name="plus" size={24}/>
              <span>Ny rapport</span>
            </button>
          </div>
        </div>
      );
    }

    // ----- Detail view -----
    const sum = data.reduce((s, d) => s + d.value, 0);
    return (
      <div className="page rapporter">
        <div className="page-header">
          <button className="link rep-back" onClick={closeR}>
            <Icon name="arrow-right" size={12}/> <span style={{ transform: 'scaleX(-1)', display: 'inline-block' }}><Icon name="arrow-right" size={12}/></span> Tilbake til rapporter
          </button>
        </div>
        <div className="rep-detail-wrap">
          <div className="rep-detail">
            <div className="rep-detail-head">
              <div>
                <h3>{openReport.name}</h3>
                <div className="dim small" style={{ marginTop: 4 }}>{periodLabel(period)}</div>
              </div>
              <div className="rep-actions">
                <Segmented
                  options={[
                    { value: 'bar', label: 'Stolpe' },
                    { value: 'waterfall', label: 'Fossefall' },
                  ]}
                  value={chartType}
                  onChange={setChartType}
                />
                <PeriodSelector period={period} onChange={onChangePeriod}/>
              </div>
            </div>

            <div className="rep-expr-builder">
              <label className="dim small">Uttrykk</label>
              <input className="rep-expr-input mono"
                     value={expression}
                     onChange={e => setExpression(e.target.value)}/>
              <div className="rep-chips">
                <span className="dim small">Variabler:</span>
                {variables.slice(0, 10).map(v => (
                  <button key={v} className="chip mono"
                          onClick={() => setExpression(expression + (expression ? ' ' : '') + v)}>
                    {v}
                  </button>
                ))}
                <span className="dim small">Operatorer:</span>
                {operators.map(op => (
                  <button key={op} className="chip mono"
                          onClick={() => setExpression(expression + ' ' + op + ' ')}>
                    {op}
                  </button>
                ))}
              </div>
            </div>

            <div className="rep-result">
              {chartType === 'waterfall' ? (
                <WaterfallChart data={data} height={300}/>
              ) : (
                <BarChart data={data} height={300}/>
              )}
            </div>

            <div className="rep-stats">
              <div className="rep-stat">
                <div className="status-label">Sum</div>
                <div className="mono med">{fmtSigned(sum)}</div>
              </div>
              <div className="rep-stat">
                <div className="status-label">Snitt</div>
                <div className="mono med">{fmtKr(sum / data.length)}</div>
              </div>
              <div className="rep-stat">
                <div className="status-label">Min</div>
                <div className="mono med">{fmtKr(Math.min(...data.map(d => d.value)))}</div>
              </div>
              <div className="rep-stat">
                <div className="status-label">Max</div>
                <div className="mono med">{fmtKr(Math.max(...data.map(d => d.value)))}</div>
              </div>
            </div>

            <div className="rep-detail-actions">
              <button className="btn-primary">Lagre endringer</button>
              <button className="btn-ghost">Dupliser</button>
              <button className="link neg-link" style={{ marginLeft: 'auto' }}>Slett rapport</button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  window.RapporterPage = RapporterPage;
})();
