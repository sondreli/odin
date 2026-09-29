// Kontoer (Accounts) page

(function () {
  const { useState } = React;

  function KontoerPage() {
    const [showAddForm, setShowAddForm] = useState(false);
    const [clientId, setClientId] = useState('');
    const [clientSecret, setClientSecret] = useState('');
    const [copied, setCopied] = useState(false);
    const [confirmDeleteId, setConfirmDeleteId] = useState(null);

    const callbackUri = 'https://odin.lurkenlark.com/api/auth/bank/callback';

    function copyToClipboard(text) {
      navigator.clipboard?.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }

    const totalBalance = ACCOUNTS.reduce((s, a) => s + a.balance, 0);

    return (
      <div className="page kontoer">
        <div className="page-header">
          <h2>Kontoer</h2>
          {!showAddForm && (
            <button className="btn-primary" onClick={() => setShowAddForm(true)}>
              <Icon name="plus" size={11}/> Koble til ny bank
            </button>
          )}
        </div>

        <div className="account-summary">
          <div className="status-label">Total saldo over alle kontoer</div>
          <div className="big-mono">{fmtKr(totalBalance)} <span className="kr-suffix">kr</span></div>
          <div className="status-sub dim">{ACCOUNTS.length} kontoer · sist synkronisert {ACCOUNTS[0].lastSync}</div>
        </div>

        <table className="txn-table accounts-table">
          <thead>
            <tr>
              <th>Konto</th>
              <th>Bank</th>
              <th>Kontonummer</th>
              <th className="a-right">Saldo</th>
              <th>Sist sync</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {ACCOUNTS.map(a => (
              <tr key={a.id} className="account-row">
                <td>
                  <div className="account-name-cell">
                    <Icon name="wallet" size={14}/>
                    <span>{a.name}</span>
                  </div>
                </td>
                <td className="dim">{a.provider}</td>
                <td className="mono dim">{a.accountNumber}</td>
                <td className="a-right mono med">{fmtKr(a.balance)} kr</td>
                <td className="dim mono small">{a.lastSync}</td>
                <td className="a-right">
                  <button className="link"><Icon name="settings" size={12}/></button>
                  <button className="link neg-link" onClick={() => setConfirmDeleteId(a.id)}>
                    Fjern
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {showAddForm && (
          <div className="add-account-form">
            <div className="form-head">
              <h3>Koble til Sparebank1 Østlandet</h3>
              <button className="icon-btn" onClick={() => setShowAddForm(false)}>
                <Icon name="close" size={14}/>
              </button>
            </div>
            <p className="dim small form-intro">
              For å koble til en bankkonto trenger du en utviklerklient fra Sparebank1.
            </p>
            <ol className="form-steps">
              <li>
                Gå til <a href="#" className="link-ext">developer.sparebank1.no</a> og opprett en utviklerkonto.
              </li>
              <li>Følg "Getting started"-guiden og opprett en ny klient.</li>
              <li>Gi klienten et valgfritt navn og lim inn callback-URLen nedenfor.</li>
              <li>Kopier klientens Client ID og Client Secret og lim inn her.</li>
            </ol>

            <div className="form-field">
              <label>Callback URI</label>
              <div className="copy-row">
                <code className="mono">{callbackUri}</code>
                <button className="btn-ghost-xs" onClick={() => copyToClipboard(callbackUri)}>
                  {copied ? 'Kopiert!' : 'Kopier'}
                </button>
              </div>
            </div>
            <div className="form-field">
              <label>Client ID</label>
              <input className="form-input" placeholder="f.eks. 516d21d1-39f1-4712-978c-..."
                     value={clientId} onChange={e => setClientId(e.target.value)}/>
            </div>
            <div className="form-field">
              <label>Client Secret</label>
              <input className="form-input" type="password" placeholder="f.eks. c6306ed8-08c9-4de3-..."
                     value={clientSecret} onChange={e => setClientSecret(e.target.value)}/>
            </div>
            <div className="form-actions">
              <button className="btn-primary" disabled={!clientId || !clientSecret}>Koble til</button>
              <button className="btn-ghost" onClick={() => { setShowAddForm(false); setClientId(''); setClientSecret(''); }}>
                Avbryt
              </button>
            </div>
          </div>
        )}

        {confirmDeleteId && (
          <div className="modal-overlay" onClick={() => setConfirmDeleteId(null)}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <p>Er du sikker på at du vil fjerne denne kontoen?</p>
              <p className="dim small">All transaksjonshistorikk vil bli slettet.</p>
              <div className="modal-actions">
                <button className="btn-danger" onClick={() => setConfirmDeleteId(null)}>Fjern</button>
                <button className="btn-ghost" onClick={() => setConfirmDeleteId(null)}>Avbryt</button>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }

  window.KontoerPage = KontoerPage;
})();
