import { useCallback, useEffect, useState } from 'react';
import { Account, ApiError, api, money, toMinor } from './api';
import TransferPanel from './TransferPanel';
import HistoryPanel from './HistoryPanel';
import LabPanel from './LabPanel';

type Tab = 'transfer' | 'history' | 'lab';

function tokenSubject(token: string): string {
  try {
    return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub;
  } catch {
    return 'unknown';
  }
}

function getStored(): string | null {
  try {
    return sessionStorage.getItem('ledgerflow.token');
  } catch {
    return null;
  }
}

export default function App() {
  const [token, setToken] = useState<string | null>(getStored);
  const [subject, setSubject] = useState('alice');
  const [error, setError] = useState('');

  const login = async () => {
    setError('');
    try {
      const t = await api.login(subject.trim());
      try {
        sessionStorage.setItem('ledgerflow.token', t);
      } catch {
        /* storage unavailable: the token just lives in memory */
      }
      setToken(t);
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 404
          ? 'The dev token endpoint is off. Start the app with LEDGER_DEV_TOKEN_ENDPOINT=true.'
          : (e as Error).message,
      );
    }
  };

  const logout = () => {
    try {
      sessionStorage.removeItem('ledgerflow.token');
    } catch {
      /* ignore */
    }
    setToken(null);
  };

  if (!token) {
    return (
      <main className="login">
        <h1>LedgerFlow</h1>
        <p className="muted">Double-entry ledger. Idempotent transfers. Transactional outbox.</p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            login();
          }}
        >
          <label>
            Sign in as (any name; a dev-only token endpoint mints a JWT for it)
            <input value={subject} onChange={(e) => setSubject(e.target.value)} maxLength={100} autoFocus />
          </label>
          <button disabled={!subject.trim()}>Sign in</button>
          {error && <p className="error">{error}</p>}
        </form>
        <p className="muted small">Each name only sees its own accounts. Try two browser windows as alice and bob.</p>
      </main>
    );
  }
  return <Dashboard token={token} onLogout={logout} />;
}

function Dashboard({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [tab, setTab] = useState<Tab>('transfer');
  const [error, setError] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [deposit, setDeposit] = useState('100.00');

  const refresh = useCallback(async () => {
    try {
      setAccounts(await api.listAccounts(token));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) onLogout();
      else setError((e as Error).message);
    }
  }, [token, onLogout]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const open = async () => {
    setError('');
    const minor = deposit.trim() === '0' ? 0 : toMinor(deposit);
    if (minor === null) return setError('Deposit must be a number with at most 2 decimals.');
    try {
      await api.openAccount(token, currency, minor);
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="app">
      <header>
        <strong>LedgerFlow</strong>
        <span className="muted">signed in as {tokenSubject(token)}</span>
        <button className="link" onClick={onLogout}>
          Sign out
        </button>
      </header>
      <div className="layout">
        <aside>
          <h2>Accounts</h2>
          {accounts.length === 0 && <p className="muted">No accounts yet. Open one below.</p>}
          <ul className="accounts">
            {accounts.map((a) => (
              <li key={a.id}>
                <div className="balance">{money(a.balanceMinor, a.currency)}</div>
                <code title={a.id}>{a.id.slice(0, 8)}</code>
                <button className="link" onClick={() => navigator.clipboard?.writeText(a.id)}>
                  copy id
                </button>
              </li>
            ))}
          </ul>
          <h3>Open an account</h3>
          <div className="row">
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
              {['USD', 'EUR', 'GBP', 'SGD'].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
            <input value={deposit} onChange={(e) => setDeposit(e.target.value)} inputMode="decimal" aria-label="Initial deposit" />
            <button onClick={open}>Open</button>
          </div>
          <p className="muted small">The deposit comes from a per-currency treasury account, so every entry still balances to zero.</p>
          {error && <p className="error">{error}</p>}
        </aside>
        <main>
          <nav className="tabs">
            {(['transfer', 'history', 'lab'] as Tab[]).map((t) => (
              <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
                {t === 'transfer' ? 'Transfer' : t === 'history' ? 'History' : 'Concurrency lab'}
              </button>
            ))}
          </nav>
          {tab === 'transfer' && <TransferPanel token={token} accounts={accounts} onDone={refresh} />}
          {tab === 'history' && <HistoryPanel token={token} accounts={accounts} />}
          {tab === 'lab' && <LabPanel token={token} onDone={refresh} />}
        </main>
      </div>
    </div>
  );
}
