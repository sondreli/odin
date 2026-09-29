// Main Odin app shell

(function () {
  const { useState, useEffect } = React;

  const NAV = [
    { id: 'transaksjoner', label: 'Transaksjoner', icon: 'list', shortcut: '1' },
    { id: 'budsjett', label: 'Budsjett', icon: 'pie', shortcut: '2' },
    { id: 'laan', label: 'Lån', icon: 'trend-up', shortcut: '3' },
    { id: 'kontoer', label: 'Kontoer', icon: 'wallet', shortcut: '4' },
    { id: 'rapporter', label: 'Rapporter', icon: 'bars', shortcut: '5' },
  ];

  const DEFAULT_TWEAKS = /*EDITMODE-BEGIN*/{
    "density": "dense",
    "categoryAccent": "vivid",
    "treemapBorders": "subtle",
    "treemapHeight": 260,
    "monoNumbers": true,
    "ribbonStyle": "ribbon",
    "showTicker": false
  }/*EDITMODE-END*/;

  function NavSidebar({ active, onChange, theme, onToggleTheme, user, collapsed, onToggleCollapsed }) {
    return (
      <nav className={'nav-sidebar' + (collapsed ? ' is-collapsed' : '')}>
        <div className="brand">
          <span className="brand-mark">◆</span>
          {!collapsed && <span className="brand-name">ODIN</span>}
          <button className="collapse-btn" onClick={onToggleCollapsed} title="Skjul/vis sidemeny">
            <Icon name={collapsed ? 'caret-right' : 'menu'} size={12}/>
          </button>
        </div>

        <ul className="nav-list">
          {NAV.map(item => (
            <li key={item.id}>
              <button
                className={'nav-item' + (active === item.id ? ' is-active' : '')}
                onClick={() => onChange(item.id)}
                title={collapsed ? item.label : ''}
              >
                <Icon name={item.icon} size={15}/>
                {!collapsed && <span>{item.label}</span>}
              </button>
            </li>
          ))}
        </ul>

        <div className="nav-foot">
          <button className="nav-item" onClick={onToggleTheme} title={theme === 'dark' ? 'Lyst tema' : 'Mørkt tema'}>
            <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={15}/>
            {!collapsed && <span>{theme === 'dark' ? 'Lyst tema' : 'Mørkt tema'}</span>}
          </button>
          {!collapsed && (
            <div className="user-block">
              <div className="user-avatar">P</div>
              <div className="user-info">
                <div className="user-email">{user}</div>
                <div className="user-status dim small">
                  <span className="live-dot"/> Synkronisert
                </div>
              </div>
            </div>
          )}
        </div>
      </nav>
    );
  }

  function TopTicker({ period }) {
    const periodTxns = TRANSACTIONS.filter(t => inPeriod(t.date, period));
    const inn = periodTxns.filter(t => t.amount > 0).reduce((s, t) => s + t.amount, 0);
    const ut = periodTxns.filter(t => t.amount < 0).reduce((s, t) => s + Math.abs(t.amount), 0);
    const net = inn - ut;
    const accountBalance = ACCOUNTS.reduce((s, a) => s + a.balance, 0);
    const totalDebt = LOANS.reduce((s, l) => s + l.remainingPrincipal, 0);

    const items = [
      { l: 'KONTO', v: fmtKr(accountBalance) + ' kr', d: '+2.1%', up: true },
      { l: 'INNTEKT', v: fmtKr(inn) + ' kr', d: '+4.4%', up: true, accent: 'var(--c-up)' },
      { l: 'UTGIFT', v: fmtKr(ut) + ' kr', d: '+8.3%', up: false, accent: 'var(--c-down)' },
      { l: 'NETTO', v: fmtSigned(net) + ' kr', d: net >= 0 ? '+12%' : '−8.3%', up: net >= 0, accent: net >= 0 ? 'var(--c-up)' : 'var(--c-down)' },
      { l: 'TRANS.', v: periodTxns.length, d: null },
      { l: 'GJELD', v: fmtKr(totalDebt) + ' kr', d: '−0.4%', up: true },
      { l: 'SPARERATE', v: ((net / inn) * 100).toFixed(1) + '%', d: null },
    ];
    return (
      <div className="top-ticker">
        {items.map((it, i) => (
          <div key={i} className="ticker-item">
            <span className="ticker-l mono">{it.l}</span>
            <span className="ticker-v mono" style={it.accent ? { color: it.accent } : null}>{it.v}</span>
            {it.d && (
              <span className={'ticker-d mono ' + (it.up ? 'up' : 'down')}>
                {it.up ? '▲' : '▼'} {it.d.replace(/^[+−]/, '')}
              </span>
            )}
          </div>
        ))}
        <div className="ticker-pulse">
          <span className="live-dot"/>
          <span className="mono small dim">LIVE · {new Date().toLocaleTimeString('nb-NO', { hour: '2-digit', minute: '2-digit' })}</span>
        </div>
      </div>
    );
  }

  function App() {
    const [active, setActive] = useState('transaksjoner');
    const [theme, setTheme] = useTheme();
    const [period, setPeriod] = useState({ type: 'month', year: 2026, month: 5 });
    const [collapsed, setCollapsed] = useState(false);
    const [tweak, setTweak] = useTweaks(DEFAULT_TWEAKS);

    // Apply density class
    useEffect(() => {
      document.documentElement.dataset.density = tweak.density;
      document.documentElement.dataset.accent = tweak.categoryAccent;
      document.documentElement.dataset.tmBorders = tweak.treemapBorders;
      document.documentElement.dataset.mono = tweak.monoNumbers ? '1' : '0';
    }, [tweak.density, tweak.categoryAccent, tweak.treemapBorders, tweak.monoNumbers]);

    // Keyboard shortcuts
    useEffect(() => {
      function onKey(e) {
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.isContentEditable) return;
        const i = NAV.findIndex(n => n.shortcut === e.key);
        if (i >= 0) setActive(NAV[i].id);
      }
      window.addEventListener('keydown', onKey);
      return () => window.removeEventListener('keydown', onKey);
    }, []);

    return (
      <div className="app-shell">
        <NavSidebar
          active={active}
          onChange={setActive}
          theme={theme}
          onToggleTheme={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
          user="pinne@lurkenlark.com"
          collapsed={collapsed}
          onToggleCollapsed={() => setCollapsed(!collapsed)}
        />
        <main className="main-area">
          {tweak.showTicker && <TopTicker period={period}/>}
          <div className="content-scroll">
            {active === 'transaksjoner' && <TransaksjonerPage period={period} onChangePeriod={setPeriod} tweak={tweak}/>}
            {active === 'budsjett' && <BudsjettPage period={period} onChangePeriod={setPeriod} tweak={tweak}/>}
            {active === 'laan' && <LanPage tweak={tweak}/>}
            {active === 'kontoer' && <KontoerPage tweak={tweak}/>}
            {active === 'rapporter' && <RapporterPage period={period} onChangePeriod={setPeriod} tweak={tweak}/>}
          </div>
        </main>

        <TweaksPanel title="Tweaks">
          <TweakSection title="Layout">
            <TweakRadio label="Tetthet" tweakKey="density" value={tweak.density} onChange={setTweak}
                        options={[
                          { value: 'dense', label: 'Tett' },
                          { value: 'comfortable', label: 'Lett' },
                        ]}/>
            <TweakToggle label="Ticker øverst" tweakKey="showTicker" value={tweak.showTicker} onChange={setTweak}/>
            <TweakSlider label="Treemap høyde" tweakKey="treemapHeight" value={tweak.treemapHeight}
                         min={160} max={420} step={20} onChange={setTweak}/>
          </TweakSection>
          <TweakSection title="Farger & stil">
            <TweakRadio label="Kategorifarger" tweakKey="categoryAccent" value={tweak.categoryAccent} onChange={setTweak}
                        options={[
                          { value: 'vivid', label: 'Knallfarger' },
                          { value: 'muted', label: 'Dempet' },
                        ]}/>
            <TweakRadio label="Treemap ramme" tweakKey="treemapBorders" value={tweak.treemapBorders} onChange={setTweak}
                        options={[
                          { value: 'subtle', label: 'Subtil' },
                          { value: 'bold', label: 'Tydelig' },
                          { value: 'none', label: 'Ingen' },
                        ]}/>
            <TweakToggle label="Monospace tall" tweakKey="monoNumbers" value={tweak.monoNumbers} onChange={setTweak}/>
          </TweakSection>
        </TweaksPanel>
      </div>
    );
  }

  window.OdinApp = App;
})();
