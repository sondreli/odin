// Lån (Loans) page

(function () {
  const { useState, useMemo } = React;

  function totalCost(l) {
    return (l.paidPrincipal || 0) + (l.paidInterest || 0) + (l.paidFees || 0)
      + l.remainingPrincipal + l.remainingInterest + (l.remainingFees || 0);
  }

  function LoanBar({ loan, maxTotal }) {
    const total = totalCost(loan);
    const segs = [
      { v: loan.paidPrincipal, color: 'var(--loan-paid-p)', label: 'Betalt avdrag' },
      { v: loan.paidInterest, color: 'var(--loan-paid-i)', label: 'Betalt rente' },
      { v: loan.paidFees, color: 'var(--loan-paid-f)', label: 'Betalte gebyr' },
      { v: loan.remainingPrincipal, color: 'var(--loan-rem-p)', label: 'Gjenst. avdrag' },
      { v: loan.remainingInterest, color: 'var(--loan-rem-i)', label: 'Gjenst. rente' },
      { v: loan.remainingFees, color: 'var(--loan-rem-f)', label: 'Gjenst. gebyr' },
    ].filter(s => s.v > 0);
    const widthPct = (total / maxTotal) * 100;
    return (
      <div className="loan-bar" style={{ width: widthPct + '%' }}>
        {segs.map((s, i) => {
          const segPct = (s.v / total) * 100;
          return (
            <div key={i} className="loan-seg" style={{ width: segPct + '%', background: s.color }}
                 title={`${s.label}: ${fmtKr(s.v)} kr`}>
              {segPct > 8 && <span className="mono">{fmtKr(s.v)}</span>}
            </div>
          );
        })}
      </div>
    );
  }

  const MONTHS_NO_FULL = ['Jan','Feb','Mar','Apr','Mai','Jun','Jul','Aug','Sep','Okt','Nov','Des'];

  function PaymentSchedule({ loan }) {
    const months = [];
    let bal = loan.remainingPrincipal;
    const now = new Date(2026, 4, 1); // May 2026
    for (let i = 1; i <= 12; i++) {
      const interest = bal * loan.interestRate / 100 / 12;
      const principal = loan.monthlyPayment - interest;
      bal -= principal;
      const d = new Date(now.getFullYear(), now.getMonth() + i, 1);
      const label = `${MONTHS_NO_FULL[d.getMonth()]} ${String(d.getFullYear()).slice(2)}`;
      months.push({ i, label, interest, principal, balance: bal });
    }
    return (
      <table className="schedule-table">
        <thead>
          <tr>
            <th>Måned</th>
            <th className="a-right">Avdrag</th>
            <th className="a-right">Rente</th>
            <th className="a-right">Termin</th>
            <th className="a-right">Rest gjeld</th>
          </tr>
        </thead>
        <tbody>
          {months.map(m => (
            <tr key={m.i}>
              <td className="mono dim">{m.label}</td>
              <td className="a-right mono">{fmtKr(m.principal)}</td>
              <td className="a-right mono dim">{fmtKr(m.interest)}</td>
              <td className="a-right mono">{fmtKr(loan.monthlyPayment)}</td>
              <td className="a-right mono">{fmtKr(m.balance)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  function PaymentHistory({ loan }) {
    // Past 12 months — back-compute from current remaining
    const months = [];
    let bal = loan.remainingPrincipal;
    const now = new Date(2026, 4, 1);
    for (let i = 0; i < 12; i++) {
      // step backward
      const interest = bal * loan.interestRate / 100 / 12;
      const principal = loan.monthlyPayment - interest;
      const beforeBal = bal + principal;
      const d = new Date(now.getFullYear(), now.getMonth() - 1 - i, 1);
      const label = `${MONTHS_NO_FULL[d.getMonth()]} ${String(d.getFullYear()).slice(2)}`;
      months.unshift({ i, label, interest, principal, balance: bal });
      bal = beforeBal;
    }
    const maxTerm = Math.max(...months.map(m => m.principal + m.interest));
    return (
      <table className="schedule-table history">
        <thead>
          <tr>
            <th>Måned</th>
            <th className="a-right">Avdrag</th>
            <th className="a-right">Rente</th>
            <th className="a-right">Termin</th>
            <th className="a-right">Rest gjeld</th>
            <th className="bar-col-h">Fordeling</th>
          </tr>
        </thead>
        <tbody>
          {months.map(m => {
            const pPct = (m.principal / maxTerm) * 100;
            const iPct = (m.interest / maxTerm) * 100;
            return (
              <tr key={m.i}>
                <td className="mono dim">{m.label}</td>
                <td className="a-right mono">{fmtKr(m.principal)}</td>
                <td className="a-right mono dim">{fmtKr(m.interest)}</td>
                <td className="a-right mono">{fmtKr(loan.monthlyPayment)}</td>
                <td className="a-right mono dim">{fmtKr(m.balance)}</td>
                <td className="bar-col">
                  <div className="hist-bar">
                    <div className="hist-bar-p" style={{ width: pPct + '%', background: 'var(--loan-paid-p)' }}/>
                    <div className="hist-bar-i" style={{ width: iPct + '%', background: 'var(--loan-paid-i)' }}/>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    );
  }

  function LoanBreakdown({ loan }) {
    const total = totalCost(loan);
    const paid = (loan.paidPrincipal || 0) + (loan.paidInterest || 0) + (loan.paidFees || 0);
    const segs = [
      { v: loan.paidPrincipal, color: 'var(--loan-paid-p)', label: 'Betalt avdrag', group: 'paid' },
      { v: loan.paidInterest, color: 'var(--loan-paid-i)', label: 'Betalt rente', group: 'paid' },
      { v: loan.paidFees, color: 'var(--loan-paid-f)', label: 'Betalte gebyr', group: 'paid' },
      { v: loan.remainingPrincipal, color: 'var(--loan-rem-p)', label: 'Gjenst. avdrag', group: 'rem' },
      { v: loan.remainingInterest, color: 'var(--loan-rem-i)', label: 'Gjenst. rente', group: 'rem' },
      { v: loan.remainingFees, color: 'var(--loan-rem-f)', label: 'Gjenst. gebyr', group: 'rem' },
    ].filter(s => s.v > 0);
    return (
      <div className="loan-breakdown">
        <div className="loan-bar full">
          {segs.map((s, i) => (
            <div key={i} className="loan-seg" style={{ width: (s.v / total) * 100 + '%', background: s.color }}
                 title={`${s.label}: ${fmtKr(s.v)} kr`}>
              {(s.v / total) * 100 > 6 && <span className="mono">{fmtKr(s.v)}</span>}
            </div>
          ))}
        </div>
        <div className="bd-grid">
          {segs.map((s, i) => (
            <div key={i} className="bd-item">
              <div className="bd-sw" style={{ background: s.color }}/>
              <div className="bd-meta">
                <div className="bd-label">{s.label}</div>
                <div className="bd-amt mono">{fmtKr(s.v)} kr</div>
                <div className="bd-pct mono dim">{((s.v / total) * 100).toFixed(1)}%</div>
              </div>
            </div>
          ))}
        </div>
        <div className="bd-summary">
          <div className="bd-sum-row">
            <span className="dim">Betalt så langt</span>
            <span className="mono pos">{fmtKr(paid)} kr</span>
            <span className="mono dim">{((paid / total) * 100).toFixed(0)}%</span>
          </div>
          <div className="bd-sum-row">
            <span className="dim">Gjenstår</span>
            <span className="mono">{fmtKr(total - paid)} kr</span>
            <span className="mono dim">{(((total - paid) / total) * 100).toFixed(0)}%</span>
          </div>
          <div className="bd-sum-row total">
            <span className="dim">Total kostnad</span>
            <span className="mono">{fmtKr(total)} kr</span>
            <span/>
          </div>
        </div>
      </div>
    );
  }

  function LoanCard({ loan, isExpanded, onToggle }) {
    const [tab, setTab] = useState('plan');
    const total = totalCost(loan);
    const paid = (loan.paidPrincipal || 0) + (loan.paidInterest || 0) + (loan.paidFees || 0);
    const paidPct = (paid / total) * 100;
    const monthsRemaining = loan.termMonths - loan.monthsPaid;
    const yearsRemaining = Math.floor(monthsRemaining / 12);
    const extraMonths = monthsRemaining % 12;

    return (
      <div className={'loan-card ' + (isExpanded ? 'is-expanded' : '')}>
        <div className="loan-card-head" onClick={onToggle}>
          <div className="loan-name-block">
            <div className="loan-name">{loan.name}</div>
            <div className="loan-provider dim small">{loan.provider}</div>
          </div>
          <div className="loan-stats">
            <div className="loan-stat">
              <div className="status-label">Rest</div>
              <div className="mono med">{fmtKr(loan.remainingPrincipal)} kr</div>
            </div>
            <div className="loan-stat">
              <div className="status-label">Rente</div>
              <div className="mono med">{loan.interestRate.toFixed(2)}%</div>
            </div>
            <div className="loan-stat">
              <div className="status-label">Termin</div>
              <div className="mono med">{fmtKr(loan.monthlyPayment)} kr</div>
            </div>
            <div className="loan-stat">
              <div className="status-label">Ferdig om</div>
              <div className="mono med">{yearsRemaining}å {extraMonths}m</div>
            </div>
            <div className="loan-stat">
              <div className="status-label">Nedbetalt</div>
              <div className="mono med">{paidPct.toFixed(0)}%</div>
            </div>
          </div>
          <Icon name={isExpanded ? 'caret-up' : 'caret-down'} size={16}/>
        </div>
        {isExpanded && (
          <div className="loan-card-body">
            <div className="loan-detail-grid">
              <div>
                <div className="status-label">Opprinnelig lånebeløp</div>
                <div className="mono">{fmtKr(loan.initialPrincipal)} kr</div>
              </div>
              <div>
                <div className="status-label">Total kostnad</div>
                <div className="mono">{fmtKr(total)} kr</div>
              </div>
              <div>
                <div className="status-label">Total rente</div>
                <div className="mono">{fmtKr((loan.paidInterest || 0) + loan.remainingInterest)} kr</div>
              </div>
              <div>
                <div className="status-label">Måneder igjen</div>
                <div className="mono">{monthsRemaining} av {loan.termMonths}</div>
              </div>
            </div>
            <div className="loan-tabs">
              {[
                { value: 'plan', label: 'Plan' },
                { value: 'fordeling', label: 'Fordeling' },
                { value: 'historikk', label: 'Historikk' },
              ].map(t => (
                <button key={t.value}
                        className={'loan-tab' + (tab === t.value ? ' is-active' : '')}
                        onClick={() => setTab(t.value)}>
                  {t.label}
                </button>
              ))}
            </div>
            {tab === 'plan' && (
              <>
                <div className="schedule-block">
                  <div className="block-title">Plan neste 12 måneder</div>
                  <PaymentSchedule loan={loan}/>
                </div>
                <div className="schedule-block">
                  <div className="block-title">Hva-om kalkulator</div>
                  <div className="whatif">
                    <label>
                      <span className="dim small">Ekstra månedlig nedbetaling</span>
                      <input type="number" defaultValue={0} className="be-input mono a-right" />
                      <span className="dim">kr</span>
                    </label>
                    <span className="dim">→</span>
                    <span className="mono med pos">Sparer 0 kr i rente</span>
                    <span className="dim">→</span>
                    <span className="mono med pos">0 mnd raskere</span>
                  </div>
                </div>
              </>
            )}
            {tab === 'fordeling' && (
              <div className="schedule-block">
                <div className="block-title">Fordeling av total kostnad</div>
                <LoanBreakdown loan={loan}/>
              </div>
            )}
            {tab === 'historikk' && (
              <div className="schedule-block">
                <div className="block-title">Historikk siste 12 måneder</div>
                <PaymentHistory loan={loan}/>
              </div>
            )}
          </div>
        )}
      </div>
    );
  }

  function LanPage() {
    const [expandedId, setExpandedId] = useState('l1');
    const maxTotal = Math.max(...LOANS.map(totalCost));

    const grandPaid = LOANS.reduce((s, l) => s + (l.paidPrincipal || 0) + (l.paidInterest || 0) + (l.paidFees || 0), 0);
    const grandRemaining = LOANS.reduce((s, l) => s + l.remainingPrincipal + l.remainingInterest + (l.remainingFees || 0), 0);
    const grandMonthly = LOANS.reduce((s, l) => s + l.monthlyPayment, 0);
    const grandPrincipal = LOANS.reduce((s, l) => s + l.remainingPrincipal, 0);

    return (
      <div className="page lan">
        <div className="page-header">
          <h2>Lån</h2>
          <button className="btn-primary"><Icon name="plus" size={11}/> Nytt lån</button>
        </div>

        <div className="loan-summary-grid">
          <div className="budget-tile">
            <div className="status-label">Total gjeld</div>
            <div className="big-mono neg">{fmtKr(grandPrincipal)} <span className="kr-suffix">kr</span></div>
            <div className="status-sub dim">{LOANS.length} aktive lån</div>
          </div>
          <div className="budget-tile">
            <div className="status-label">Månedlig betaling</div>
            <div className="big-mono">{fmtKr(grandMonthly)} <span className="kr-suffix">kr</span></div>
            <div className="status-sub dim">samlet termin</div>
          </div>
          <div className="budget-tile">
            <div className="status-label">Betalt totalt</div>
            <div className="big-mono pos">{fmtKr(grandPaid)} <span className="kr-suffix">kr</span></div>
            <div className="status-sub dim">avdrag + rente + gebyr</div>
          </div>
          <div className="budget-tile">
            <div className="status-label">Snittrente</div>
            <div className="big-mono">{(LOANS.reduce((s, l) => s + l.interestRate * l.remainingPrincipal, 0) / grandPrincipal).toFixed(2)}<span className="kr-suffix">%</span></div>
            <div className="status-sub dim">veid på gjeld</div>
          </div>
        </div>

        <div className="loan-comparison">
          <div className="block-title">Sammenligning</div>
          <div className="loan-legend">
            {[
              ['var(--loan-paid-p)', 'Betalt avdrag'],
              ['var(--loan-paid-i)', 'Betalt rente'],
              ['var(--loan-paid-f)', 'Betalte gebyr'],
              ['var(--loan-rem-p)', 'Gjenst. avdrag'],
              ['var(--loan-rem-i)', 'Gjenst. rente'],
              ['var(--loan-rem-f)', 'Gjenst. gebyr'],
            ].map(([c, l]) => (
              <div key={l} className="legend-item">
                <span className="legend-sw" style={{ background: c }}/>
                <span>{l}</span>
              </div>
            ))}
          </div>
          <div className="loan-bars">
            {LOANS.map(l => (
              <div key={l.id} className="loan-bar-row">
                <div className="loan-bar-label">{l.name}</div>
                <LoanBar loan={l} maxTotal={maxTotal}/>
              </div>
            ))}
          </div>
        </div>

        <div className="loan-cards">
          {LOANS.map(l => (
            <LoanCard
              key={l.id}
              loan={l}
              isExpanded={expandedId === l.id}
              onToggle={() => setExpandedId(expandedId === l.id ? null : l.id)}
            />
          ))}
        </div>
      </div>
    );
  }

  window.LanPage = LanPage;
})();
