// Transaksjoner page — treemap + transactions table + categories sidebar

(function () {
  const { useState, useMemo, useRef, useEffect } = React;

  function CategoriesSidebar({ categories, tags, txns, selectedCategoryId, onSelectCategory, period, onAddTag }) {
    const [expandedCatId, setExpandedCatId] = useState(null);
    const [showAddCat, setShowAddCat] = useState(false);
    const [newCatName, setNewCatName] = useState('');
    const [showAddTag, setShowAddTag] = useState(false);
    const [newTagName, setNewTagName] = useState('');

    const txnsByCat = useMemo(() => {
      const m = {};
      txns.forEach(t => {
        if (!m[t.categoryId]) m[t.categoryId] = [];
        m[t.categoryId].push(t);
      });
      return m;
    }, [txns]);

    return (
      <div className="sidebar-panel">
        <div className="sidebar-title">
          <span>Kategorier</span>
          <span className="count mono">{categories.length}</span>
        </div>
        <table className="sidebar-table">
          <tbody>
            {categories.map(cat => {
              const count = (txnsByCat[cat.id] || []).length;
              const isSelected = selectedCategoryId === cat.id;
              const isExpanded = expandedCatId === cat.id;
              return (
                <React.Fragment key={cat.id}>
                  <tr className={isSelected ? 'is-selected' : ''}>
                    <td className="sb-edit">
                      <button className="link"
                              onClick={() => setExpandedCatId(isExpanded ? null : cat.id)}>
                        {isExpanded ? 'Lukk' : 'Endre'}
                      </button>
                    </td>
                    <td className="sb-name"
                        onClick={() => onSelectCategory(isSelected ? null : cat.id)}>
                      <span className="cat-swatch" style={{ background: cat.color }}></span>
                      <span>{cat.name}</span>
                      {cat.bucket && <span className="bucket-tag">{cat.bucket}</span>}
                    </td>
                    <td className="sb-count mono">{count}</td>
                  </tr>
                  {isExpanded && (
                    <tr>
                      <td colSpan={3} className="sb-edit-panel">
                        <div className="sb-filters-label">Filtre</div>
                        <div className="sb-filter-list">
                          <input className="sb-filter-input" defaultValue="REMA" />
                          <input className="sb-filter-input" defaultValue="BUNNPRIS" />
                          <input className="sb-filter-input" defaultValue="COOP PRIX" />
                          <input className="sb-filter-input" defaultValue="MENY" />
                          <input className="sb-filter-input" defaultValue="KIWI" />
                          <button className="link plus-link"><Icon name="plus" size={11}/> Ny filter</button>
                        </div>
                        <div className="sb-edit-actions">
                          <button className="btn-primary-xs">Lagre</button>
                          <button className="btn-ghost-xs">Slett</button>
                        </div>
                      </td>
                    </tr>
                  )}
                </React.Fragment>
              );
            })}
          </tbody>
        </table>
        {showAddCat ? (
          <div className="sb-new-cat">
            <input
              className="sb-input"
              placeholder="Kategorinavn"
              value={newCatName}
              onChange={e => setNewCatName(e.target.value)}
              autoFocus
            />
            <div className="sb-new-cat-actions">
              <button className="btn-primary-xs" onClick={() => { setShowAddCat(false); setNewCatName(''); }}>Lagre</button>
              <button className="btn-ghost-xs" onClick={() => { setShowAddCat(false); setNewCatName(''); }}>Avbryt</button>
            </div>
          </div>
        ) : (
          <button className="link sb-add" onClick={() => setShowAddCat(true)}>
            <Icon name="plus" size={11}/> Ny kategori
          </button>
        )}

        <div className="sidebar-title" style={{ marginTop: 16 }}>
          <span>Tags</span>
          <span className="count mono">{tags.length}</span>
        </div>
        <div className="tags-list">
          {tags.map(t => (
            <div key={t.id} className="tag-row">
              <span className="tag-dot" style={{ background: t.color }}></span>
              <span>{t.name}</span>
              <span className="mono tag-count">{txns.filter(tx => (tx.tagIds || []).includes(t.id)).length}</span>
              <button className="link icon-link"><Icon name="close" size={10}/></button>
            </div>
          ))}
        </div>
        {showAddTag ? (
          <div className="sb-new-cat">
            <input className="sb-input" placeholder="Tagnavn" value={newTagName}
                   onChange={e => setNewTagName(e.target.value)} autoFocus />
            <div className="sb-new-cat-actions">
              <button className="btn-primary-xs" onClick={() => { setShowAddTag(false); setNewTagName(''); }}>Lagre</button>
              <button className="btn-ghost-xs" onClick={() => { setShowAddTag(false); setNewTagName(''); }}>Avbryt</button>
            </div>
          </div>
        ) : (
          <button className="link sb-add" onClick={() => setShowAddTag(true)}>
            <Icon name="plus" size={11}/> Ny tag
          </button>
        )}
      </div>
    );
  }

  function TransactionsTable({ txns, categories, tags, editingId, onEdit, selectedTxnIds, onToggleSelect, multiSelect, sortCol, sortDir, onSort, filterText }) {
    const catMap = useMemo(() => Object.fromEntries(categories.map(c => [c.id, c])), [categories]);
    const tagMap = useMemo(() => Object.fromEntries(tags.map(t => [t.id, t])), [tags]);

    let filtered = txns;
    if (filterText) {
      const ft = filterText.toLowerCase();
      filtered = filtered.filter(t =>
        t.description.toLowerCase().includes(ft) ||
        (catMap[t.categoryId]?.name || '').toLowerCase().includes(ft));
    }

    const sorted = useMemo(() => {
      const list = [...filtered];
      const dir = sortDir === 'desc' ? -1 : 1;
      list.sort((a, b) => {
        if (sortCol === 'amount') return (a.amount - b.amount) * dir;
        if (sortCol === 'date') return (dateToTs(a.date) - dateToTs(b.date)) * dir;
        if (sortCol === 'description') return a.description.localeCompare(b.description) * dir;
        return 0;
      });
      return list;
    }, [filtered, sortCol, sortDir]);

    function SortHead({ col, children, align = 'left' }) {
      const sigil = sortCol === col ? (sortDir === 'desc' ? '▼' : '▲') : '△';
      return (
        <th onClick={() => onSort(col)} className={'sortable a-' + align}>
          <span>{children}</span>
          <span className="sort-sigil mono">{sigil}</span>
        </th>
      );
    }

    return (
      <table className="txn-table">
        <thead>
          <tr>
            <th style={{ width: 36 }}></th>
            <th style={{ width: 6 }}></th>
            <SortHead col="amount" align="right">Beløp</SortHead>
            <SortHead col="date" align="right">Dato</SortHead>
            <SortHead col="description">Beskrivelse</SortHead>
            <th style={{ width: 36 }}></th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((t, i) => {
            const cat = catMap[t.categoryId];
            const isEditing = editingId === t.id;
            const isSelected = (selectedTxnIds || []).includes(t.id);
            return (
              <React.Fragment key={t.id}>
                <tr className={'txn-row ' + (isEditing ? 'is-editing' : '') + (isSelected ? ' is-selected' : '')}>
                  <td className="a-center">
                    {multiSelect ? (
                      <input type="checkbox" checked={isSelected}
                             onChange={() => onToggleSelect(t.id)} />
                    ) : (
                      <button className="link" onClick={() => onEdit(isEditing ? null : t.id)}>
                        {isEditing ? 'Lukk' : 'Endre'}
                      </button>
                    )}
                  </td>
                  <td className="cat-stripe" style={{ background: cat ? cat.color : 'transparent', '--_cat-color': cat ? cat.color : 'transparent' }}></td>
                  <td className={'a-right mono amt ' + (t.amount > 0 ? 'pos' : 'neg')}>
                    {fmtKr(t.amount, 2)}
                  </td>
                  <td className="a-right mono dim">{t.date}</td>
                  <td className="desc">
                    {filterText ? <HighlightedText text={t.description} query={filterText}/> : t.description}
                    {(t.tagIds || []).map(tid => {
                      const tag = tagMap[tid];
                      if (!tag) return null;
                      return <span key={tid} className="tag-dot inline" style={{ background: tag.color }} title={tag.name}/>;
                    })}
                    {t.markedByFilter && <span className="filter-mark" title="markert av filter">●</span>}
                  </td>
                  <td className="a-center dim">
                    {t.markedByFilter && <button className="link tiny">View</button>}
                  </td>
                </tr>
                {isEditing && (
                  <tr className="txn-edit-panel">
                    <td colSpan={6}>
                      <div className="txn-edit-flex">
                        <button className="btn-primary-xs">Lagre</button>
                        <span className="dim small">Kategori</span>
                        <select className="txn-select" defaultValue={t.categoryId || ''}>
                          <option value="">— velg —</option>
                          {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                        </select>
                        <span className="dim small">Filter</span>
                        <input className="txn-input" defaultValue={t.description.split(' ')[0]} />
                        <span className="dim small" style={{ marginLeft: 'auto' }}>Tags</span>
                        {tags.map(tag => {
                          const selected = (t.tagIds || []).includes(tag.id);
                          return (
                            <button key={tag.id}
                                    className={'tag-chip ' + (selected ? 'is-selected' : '')}
                                    style={selected ? { background: tag.color, borderColor: tag.color } : null}>
                              {tag.name}
                            </button>
                          );
                        })}
                      </div>
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    );
  }

  function HighlightedText({ text, query }) {
    if (!query) return text;
    const i = text.toLowerCase().indexOf(query.toLowerCase());
    if (i < 0) return text;
    return (
      <>
        {text.slice(0, i)}
        <mark className="hl">{text.slice(i, i + query.length)}</mark>
        {text.slice(i + query.length)}
      </>
    );
  }

  function BarChartView({ txns, categories }) {
    const catMap = useMemo(() => Object.fromEntries(categories.map(c => [c.id, c])), [categories]);
    // Group by day
    const byDay = useMemo(() => {
      const m = {};
      txns.forEach(t => {
        if (t.amount > 0) return; // expenses only
        if (!m[t.date]) m[t.date] = {};
        const cid = t.categoryId || 'ukat-ut';
        m[t.date][cid] = (m[t.date][cid] || 0) + Math.abs(t.amount);
      });
      // sort by ts
      return Object.entries(m).sort(([a], [b]) => dateToTs(a) - dateToTs(b));
    }, [txns]);

    const totals = byDay.map(([, cats]) => Object.values(cats).reduce((s, v) => s + v, 0));
    const maxDay = Math.max(1, ...totals);
    return (
      <div className="bar-chart-wrap">
        <div className="bar-chart">
          {byDay.map(([day, cats], i) => {
            const total = totals[i];
            const h = (total / maxDay) * 100;
            const sortedCats = Object.entries(cats).sort((a, b) => b[1] - a[1]);
            return (
              <div key={day} className="bar-col" title={`${day}: ${fmtKr(total)} kr`}>
                <div className="bar-stack" style={{ height: h + '%' }}>
                  {sortedCats.map(([cid, val]) => (
                    <div key={cid}
                         style={{ background: catMap[cid]?.color || '#888', flex: val }}
                         title={`${catMap[cid]?.name || cid}: ${fmtKr(val)}`}>
                    </div>
                  ))}
                </div>
                <div className="bar-label mono">{day.split('.').slice(0, 2).join('.')}</div>
              </div>
            );
          })}
        </div>
        <div className="bar-y-axis mono">
          <span>{fmtKr(maxDay)} kr</span>
          <span>{fmtKr(maxDay/2)}</span>
          <span>0</span>
        </div>
      </div>
    );
  }

  function SummedTable({ txns, categories }) {
    // Group: for each category, sum amount and count
    const grouped = useMemo(() => {
      const m = {};
      categories.forEach(c => m[c.id] = { ...c, total: 0, count: 0, avg: 0 });
      txns.forEach(t => {
        if (!m[t.categoryId]) return;
        m[t.categoryId].total += t.amount;
        m[t.categoryId].count += 1;
      });
      const arr = Object.values(m).filter(c => c.count > 0);
      arr.forEach(c => { c.avg = c.total / c.count; });
      return arr.sort((a, b) => a.total - b.total);
    }, [txns, categories]);

    const totalExpense = grouped.filter(g => g.total < 0).reduce((s, g) => s + g.total, 0);
    const totalIncome = grouped.filter(g => g.total > 0).reduce((s, g) => s + g.total, 0);

    return (
      <table className="txn-table summed">
        <thead>
          <tr>
            <th style={{ width: 6 }}></th>
            <th>Kategori</th>
            <th className="a-right">Antall</th>
            <th className="a-right">Snitt</th>
            <th className="a-right">Sum</th>
            <th>Andel</th>
          </tr>
        </thead>
        <tbody>
          {grouped.map(g => {
            const ref = g.total < 0 ? totalExpense : totalIncome;
            const pct = ref ? (g.total / ref) * 100 : 0;
            return (
              <tr key={g.id}>
                <td className="cat-stripe" style={{ background: g.color }}></td>
                <td><span className="cat-mini" style={{ background: g.color }}>{g.name}</span></td>
                <td className="a-right mono dim">{g.count}</td>
                <td className="a-right mono dim">{fmtKr(g.avg, 0)}</td>
                <td className={'a-right mono ' + (g.total < 0 ? 'neg' : 'pos')}>{fmtKr(g.total, 0)}</td>
                <td>
                  <div className="andel-bar">
                    <div style={{ width: pct + '%', background: g.color }}/>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    );
  }

  function TransaksjonerPage({ period, onChangePeriod, tweak }) {
    const [view, setView] = useState('table'); // table | bar | summed
    const [showBudget, setShowBudget] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [selectedCategoryId, setSelectedCategoryId] = useState(null);
    const [hoveredTreemapId, setHoveredTreemapId] = useState(null);
    const [editingTxnId, setEditingTxnId] = useState(null);
    const [filterText, setFilterText] = useState('');
    const [sortCol, setSortCol] = useState('date');
    const [sortDir, setSortDir] = useState('desc');
    const [multiSelect, setMultiSelect] = useState(false);
    const [selectedTxnIds, setSelectedTxnIds] = useState([]);

    const allTxns = TRANSACTIONS;
    const periodTxns = useMemo(() =>
      allTxns.filter(t => inPeriod(t.date, period)),
      [allTxns, period]);

    const filteredByCat = selectedCategoryId
      ? periodTxns.filter(t => t.categoryId === selectedCategoryId)
      : periodTxns;

    const summaries = useMemo(() => {
      const m = {};
      CATEGORIES.forEach(c => { m[c.id] = { ...c, amount: 0, target: CATEGORY_TARGETS[c.id] || null }; });
      periodTxns.forEach(t => {
        if (m[t.categoryId]) m[t.categoryId].amount += t.amount;
      });
      return Object.values(m);
    }, [periodTxns]);

    const expenseCats = summaries.filter(c => c.amount < 0);
    const totalExpense = expenseCats.reduce((s, c) => s + Math.abs(c.amount), 0);
    const totalIncome = summaries.filter(c => c.amount > 0).reduce((s, c) => s + c.amount, 0);
    const net = totalIncome - totalExpense;

    const treemapItems = expenseCats
      .filter(c => Math.abs(c.amount) > 0)
      .map(c => ({ id: c.id, name: c.name, color: c.color, value: Math.abs(c.amount), target: c.target }));

    function handleSort(col) {
      if (col === sortCol) setSortDir(sortDir === 'desc' ? 'asc' : 'desc');
      else { setSortCol(col); setSortDir('desc'); }
    }

    return (
      <div className="page transaksjoner">
        {/* Top metrics ticker */}
        <div className="metrics-bar">
          <StatusTile label="Saldo" value={fmtKr(45523) + ' kr'} sub="Brukskonto" />
          <StatusTile label="Inn" value={fmtKr(totalIncome)} accent="var(--c-up)" sub={periodLabel(period)} />
          <StatusTile label="Ut" value={fmtKr(totalExpense)} accent="var(--c-down)" sub={`${periodTxns.length} trans.`} />
          <StatusTile label="Netto" value={fmtSigned(net)} accent={net >= 0 ? 'var(--c-up)' : 'var(--c-down)'} delta={net >= 0 ? 4.2 : -8.3} />
          <StatusTile label="Sparerate" value={(net / totalIncome * 100).toFixed(1) + '%'} sub="vs 12% mål" />
          <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8 }}>
            <label className="check-inline">
              <input type="checkbox" checked={showBudget} onChange={() => setShowBudget(!showBudget)}/>
              <span>Vis budsjett</span>
            </label>
            <label className="check-inline">
              <input type="checkbox" checked={showFilters} onChange={() => setShowFilters(!showFilters)}/>
              <span>Vis filtre</span>
            </label>
          </div>
        </div>

        {/* Treemap */}
        <div className="treemap-wrap">
          <Treemap
            items={treemapItems}
            height={tweak.treemapHeight || 260}
            hoveredId={hoveredTreemapId}
            selectedName={selectedCategoryId ? CATEGORIES.find(c => c.id === selectedCategoryId)?.name : null}
            onHover={setHoveredTreemapId}
            onClick={(item) => setSelectedCategoryId(selectedCategoryId === item.id ? null : item.id)}
            accent="var(--treemap-border)"
          />
          {/* Spending bar with arrow */}
          <div className="treemap-foot">
            <div className="treemap-spending-bar">
              <span className="mono">▲ {fmtKr(totalExpense)} brukt</span>
              {showBudget && (
                <span className="mono dim">
                  {' '}/ {fmtKr(Object.values(CATEGORY_TARGETS).reduce((s, v) => s + v, 0))} budsjett
                </span>
              )}
            </div>
            {selectedCategoryId && (
              <button className="filter-clear" onClick={() => setSelectedCategoryId(null)}>
                <Icon name="close" size={10}/> Fjern filter
              </button>
            )}
          </div>
        </div>

        {/* Controls row */}
        <div className="controls-row">
          <Segmented
            options={[
              { value: 'table', label: 'Tabell' },
              { value: 'bar', label: 'Stolpe' },
              { value: 'summed', label: 'Sum' },
            ]}
            value={view}
            onChange={setView}
          />
          <PeriodSelector period={period} onChange={onChangePeriod}/>
          <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8 }}>
            <div className="search-wrap">
              <Icon name="search" size={12}/>
              <input
                value={filterText}
                onChange={e => setFilterText(e.target.value)}
                placeholder="Søk i transaksjoner…"
              />
            </div>
            <button className={'icon-btn' + (multiSelect ? ' is-active' : '')}
                    title="Velg flere"
                    onClick={() => { setMultiSelect(!multiSelect); setSelectedTxnIds([]); }}>
              <Icon name="check" size={13}/>
            </button>
          </div>
        </div>

        {/* Filter path */}
        <div className="filter-path">
          <button className="link" onClick={() => setSelectedCategoryId(null)}>All</button>
          {selectedCategoryId && (
            <>
              <span className="sep">›</span>
              <span className="path-cat">
                <span className="cat-swatch" style={{ background: CATEGORIES.find(c => c.id === selectedCategoryId)?.color }}/>
                <span>{CATEGORIES.find(c => c.id === selectedCategoryId)?.name}</span>
              </span>
            </>
          )}
          <span className="dim small" style={{ marginLeft: 'auto' }}>
            {filteredByCat.length} transaksjoner · {fmtKr(filteredByCat.reduce((s, t) => s + t.amount, 0))} kr
          </span>
        </div>

        {/* Main content split */}
        <div className="split-content">
          <div className="content-main">
            {multiSelect && selectedTxnIds.length > 0 && (
              <div className="multi-select-bar">
                <span className="mono">{selectedTxnIds.length} valgt</span>
                <span className="dim">·</span>
                <span className="mono">
                  Sum: {fmtSigned(filteredByCat.filter(t => selectedTxnIds.includes(t.id)).reduce((s, t) => s + t.amount, 0))}
                </span>
                <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
                  <button className="btn-primary-xs">Tildel kategori</button>
                  <button className="btn-ghost-xs">Avbryt</button>
                </div>
              </div>
            )}
            {view === 'table' && (
              <TransactionsTable
                txns={filteredByCat}
                categories={CATEGORIES}
                tags={TAGS}
                editingId={editingTxnId}
                onEdit={setEditingTxnId}
                selectedTxnIds={selectedTxnIds}
                onToggleSelect={(id) => setSelectedTxnIds(ids => ids.includes(id) ? ids.filter(x => x !== id) : [...ids, id])}
                multiSelect={multiSelect}
                sortCol={sortCol}
                sortDir={sortDir}
                onSort={handleSort}
                filterText={filterText}
              />
            )}
            {view === 'bar' && <BarChartView txns={filteredByCat} categories={CATEGORIES}/>}
            {view === 'summed' && <SummedTable txns={filteredByCat} categories={CATEGORIES}/>}
          </div>
          <CategoriesSidebar
            categories={CATEGORIES}
            tags={TAGS}
            txns={periodTxns}
            selectedCategoryId={selectedCategoryId}
            onSelectCategory={setSelectedCategoryId}
            period={period}
          />
        </div>
      </div>
    );
  }

  window.TransaksjonerPage = TransaksjonerPage;
})();
