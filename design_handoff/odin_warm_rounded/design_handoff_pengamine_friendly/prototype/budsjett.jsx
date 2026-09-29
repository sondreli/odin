// Budsjett page — needs/wants/should buckets with targets vs spend

(function () {
  const { useState, useMemo } = React;

  const BUCKET_LABELS = {
    needs: { name: 'Behov', sub: 'Mat, bolig, transport — det som må betales', target: 0.55 },
    wants: { name: 'Ønsker', sub: 'Aktiviteter, ting, ferie — for livskvalitet', target: 0.30 },
    should: { name: 'Bør', sub: 'Sparing, nedbetaling — for fremtiden', target: 0.15 },
  };

  function BudgetBar({ spent, target, color, height = 8 }) {
    const max = Math.max(spent, target, 1);
    const spentPct = (spent / max) * 100;
    const targetPct = (target / max) * 100;
    const over = spent > target;
    return (
      <div className="budget-bar-wrap" style={{ height }}>
        <div className="budget-bar-track"/>
        <div className="budget-bar-fill" style={{ width: spentPct + '%', background: color, opacity: over ? 1 : 0.85 }}/>
        {over && (
          <div className="budget-bar-overbar" style={{
            left: targetPct + '%',
            width: (spentPct - targetPct) + '%',
            background: `repeating-linear-gradient(45deg, ${color} 0 4px, var(--c-down) 4px 8px)`,
          }}/>
        )}
        <div className="budget-bar-target-mark" style={{ left: targetPct + '%' }}/>
      </div>
    );
  }

  function CategoryRow({ cat, editingId, onEdit }) {
    const spent = Math.abs(cat.amount);
    const target = cat.target || 0;
    const diff = target - spent;
    const pct = target ? (spent / target) * 100 : null;
    const isEditing = editingId === cat.id;
    return (
      <>
        <tr className={'budget-row ' + (isEditing ? 'is-editing' : '')}>
          <td>
            <button className="link" onClick={() => onEdit(isEditing ? null : cat.id)}>
              {isEditing ? 'Lukk' : 'Endre'}
            </button>
          </td>
          <td className="cat-stripe" style={{ background: cat.color, '--_cat-color': cat.color, width: 6 }}/>
          <td className="cat-name">
            <span>{cat.name}</span>
          </td>
          <td className="a-right mono">
            {target ? fmtKr(target) : <span className="dim">—</span>}
          </td>
          <td className="a-right mono">{fmtKr(spent)}</td>
          <td className={'a-right mono ' + (diff >= 0 ? 'pos' : 'neg')}>
            {target ? (diff >= 0 ? '+' : '−') + fmtKr(Math.abs(diff)) : <span className="dim">—</span>}
          </td>
          <td className="a-right mono dim" style={{ width: 56 }}>
            {pct != null ? pct.toFixed(0) + '%' : ''}
          </td>
          <td className="budget-bar-cell">
            {target > 0 && <BudgetBar spent={spent} target={target} color={cat.color}/>}
          </td>
        </tr>
        {isEditing && (
          <tr className="budget-edit-panel">
            <td colSpan={8}>
              <div className="budget-edit">
                <div className="be-block">
                  <span className="dim small">Mål per måned</span>
                  <input className="be-input mono a-right" defaultValue={target}/>
                  <span className="dim">kr</span>
                </div>
                <div className="be-block">
                  <span className="dim small">Bøtte</span>
                  <select className="be-select" defaultValue={cat.bucket || ''}>
                    <option value="">— ingen —</option>
                    <option value="needs">Behov</option>
                    <option value="wants">Ønsker</option>
                    <option value="should">Bør</option>
                  </select>
                </div>
                <div className="be-block">
                  <span className="dim small">Rollover ubrukt</span>
                  <input type="checkbox" defaultChecked={false}/>
                </div>
                <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
                  <button className="btn-primary-xs">Lagre</button>
                  <button className="btn-ghost-xs">Slett</button>
                </div>
              </div>
            </td>
          </tr>
        )}
      </>
    );
  }

  function BucketSection({ bucket, categories, editingId, onEdit }) {
    const meta = BUCKET_LABELS[bucket];
    const totalSpent = categories.reduce((s, c) => s + Math.abs(c.amount), 0);
    const totalTarget = categories.reduce((s, c) => s + (c.target || 0), 0);
    const diff = totalTarget - totalSpent;
    const pct = totalTarget ? (totalSpent / totalTarget) * 100 : 0;
    return (
      <>
        <tr className="bucket-header">
          <td colSpan={8}>
            <div className="bucket-head-flex">
              <span className="bucket-name">{meta.name}</span>
              <span className="dim small">{meta.sub}</span>
              <div className="bucket-meta mono">
                <span className={pct > 100 ? 'neg' : ''}>{pct.toFixed(0)}%</span>
                <span className="dim">{fmtKr(totalSpent)} / {fmtKr(totalTarget)}</span>
              </div>
            </div>
          </td>
        </tr>
        {categories.map(c => (
          <CategoryRow key={c.id} cat={c} editingId={editingId} onEdit={onEdit}/>
        ))}
      </>
    );
  }

  function BudsjettPage({ period, onChangePeriod, tweak }) {
    const [editingId, setEditingId] = useState(null);

    const summaries = useMemo(() => {
      const m = {};
      CATEGORIES.forEach(c => {
        m[c.id] = { ...c, amount: 0, target: CATEGORY_TARGETS[c.id] || null };
      });
      TRANSACTIONS.filter(t => inPeriod(t.date, period)).forEach(t => {
        if (m[t.categoryId]) m[t.categoryId].amount += t.amount;
      });
      return Object.values(m);
    }, [period]);

    const incomeCats = summaries.filter(c => c.amount > 0);
    const expenseCats = summaries.filter(c => c.amount < 0 || c.target);
    const grouped = {
      needs: expenseCats.filter(c => c.bucket === 'needs'),
      wants: expenseCats.filter(c => c.bucket === 'wants'),
      should: expenseCats.filter(c => c.bucket === 'should'),
    };
    const unbucketed = expenseCats.filter(c => !c.bucket);

    const totalIncome = incomeCats.reduce((s, c) => s + c.amount, 0);
    const totalSpent = expenseCats.reduce((s, c) => s + Math.abs(c.amount), 0);
    const totalTarget = expenseCats.reduce((s, c) => s + (c.target || 0), 0);
    const remaining = totalIncome - totalSpent;
    const remainingBudget = totalTarget - totalSpent;

    // 50/30/20 distribution actuals
    const bucketTotals = {
      needs: grouped.needs.reduce((s, c) => s + Math.abs(c.amount), 0),
      wants: grouped.wants.reduce((s, c) => s + Math.abs(c.amount), 0),
      should: grouped.should.reduce((s, c) => s + Math.abs(c.amount), 0),
    };
    const bucketTargets = {
      needs: grouped.needs.reduce((s, c) => s + (c.target || 0), 0),
      wants: grouped.wants.reduce((s, c) => s + (c.target || 0), 0),
      should: grouped.should.reduce((s, c) => s + (c.target || 0), 0),
    };

    return (
      <div className="page budsjett">
        <div className="page-header">
          <h2>Budsjett</h2>
          <PeriodSelector period={period} onChange={onChangePeriod}/>
        </div>

        {/* Big summary tiles */}
        <div className="budget-summary-grid">
          <div className="budget-tile">
            <div className="status-label">Inntekt</div>
            <div className="big-mono pos">{fmtKr(totalIncome)} <span className="kr-suffix">kr</span></div>
            <div className="status-sub dim">denne måned</div>
          </div>
          <div className="budget-tile">
            <div className="status-label">Brukt</div>
            <div className="big-mono">{fmtKr(totalSpent)} <span className="kr-suffix">kr</span></div>
            <div className="status-sub dim">av {fmtKr(totalTarget)} budsjett</div>
          </div>
          <div className="budget-tile">
            <div className="status-label">Disponibelt</div>
            <div className={'big-mono ' + (remaining >= 0 ? 'pos' : 'neg')}>{fmtSigned(remaining)} <span className="kr-suffix">kr</span></div>
            <div className="status-sub dim">inntekt − utgifter</div>
          </div>
          <div className="budget-tile">
            <div className="status-label">Igjen i budsjett</div>
            <div className={'big-mono ' + (remainingBudget >= 0 ? 'pos' : 'neg')}>{fmtSigned(remainingBudget)} <span className="kr-suffix">kr</span></div>
            <div className="status-sub dim">{fmtPct(Math.max(0, remainingBudget) / totalTarget * 100, 0)} av budsjett</div>
          </div>
        </div>

        {/* 50/30/15 ribbon */}
        <div className="ribbon">
          {Object.entries(BUCKET_LABELS).map(([key, meta]) => {
            const spent = bucketTotals[key];
            const target = bucketTargets[key];
            const pct = totalIncome ? (spent / totalIncome) * 100 : 0;
            const targetPctOfIncome = meta.target * 100;
            return (
              <div key={key} className="ribbon-cell">
                <div className="ribbon-header">
                  <span className="ribbon-name">{meta.name}</span>
                  <span className="ribbon-pct mono">
                    {pct.toFixed(0)}% <span className="dim">/ {targetPctOfIncome.toFixed(0)}%</span>
                  </span>
                </div>
                <div className="ribbon-bar">
                  <div className="ribbon-bar-fill" style={{
                    width: Math.min(100, (spent / target) * 100) + '%',
                    background: pct > targetPctOfIncome ? 'var(--c-down)' : 'var(--c-up)',
                  }}/>
                </div>
                <div className="ribbon-foot mono">
                  <span>{fmtKr(spent)}</span>
                  <span className="dim">/ {fmtKr(target)}</span>
                </div>
              </div>
            );
          })}
        </div>

        {/* Detail tables by bucket */}
        <div className="budget-table-wrap">
          <table className="txn-table budget-table">
            <thead>
              <tr>
                <th style={{ width: 50 }}></th>
                <th style={{ width: 6 }}></th>
                <th>Kategori</th>
                <th className="a-right">Mål</th>
                <th className="a-right">Brukt</th>
                <th className="a-right">Differanse</th>
                <th className="a-right">%</th>
                <th>Progresjon</th>
              </tr>
            </thead>
            <tbody>
              <tr className="bucket-header inc">
                <td colSpan={8}>
                  <div className="bucket-head-flex">
                    <span className="bucket-name">Inntekt</span>
                    <span className="dim small">Innkommende penger</span>
                    <div className="bucket-meta mono">
                      <span className="pos">{fmtKr(totalIncome)}</span>
                    </div>
                  </div>
                </td>
              </tr>
              {incomeCats.map(c => (
                <tr key={c.id} className="budget-row">
                  <td/>
                  <td className="cat-stripe" style={{ background: c.color, '--_cat-color': c.color }}/>
                  <td>{c.name}</td>
                  <td/>
                  <td className="a-right mono pos">{fmtKr(c.amount)}</td>
                  <td/>
                  <td/>
                  <td/>
                </tr>
              ))}
              <BucketSection bucket="needs" categories={grouped.needs} editingId={editingId} onEdit={setEditingId}/>
              <BucketSection bucket="wants" categories={grouped.wants} editingId={editingId} onEdit={setEditingId}/>
              <BucketSection bucket="should" categories={grouped.should} editingId={editingId} onEdit={setEditingId}/>
              {unbucketed.length > 0 && (
                <>
                  <tr className="bucket-header">
                    <td colSpan={8}>
                      <div className="bucket-head-flex">
                        <span className="bucket-name">Ukategorisert</span>
                      </div>
                    </td>
                  </tr>
                  {unbucketed.map(c => (
                    <CategoryRow key={c.id} cat={c} editingId={editingId} onEdit={setEditingId}/>
                  ))}
                </>
              )}
              <tr className="grand-total">
                <td/>
                <td/>
                <td>Resultat</td>
                <td className="a-right mono">{fmtKr(totalTarget)}</td>
                <td className="a-right mono">{fmtKr(totalSpent)}</td>
                <td className={'a-right mono ' + (remaining >= 0 ? 'pos' : 'neg')}>
                  {fmtSigned(remaining)}
                </td>
                <td/>
                <td/>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    );
  }

  window.BudsjettPage = BudsjettPage;
})();
