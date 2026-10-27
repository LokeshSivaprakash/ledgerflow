import { useCallback, useEffect, useState } from 'react';
import { Account, Entry, api, money } from './api';

export default function HistoryPanel({ token, accounts }: { token: string; accounts: Account[] }) {
  const [id, setId] = useState('');
  const [entries, setEntries] = useState<Entry[]>([]);
  const [next, setNext] = useState<number | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!id && accounts.length) setId(accounts[0].id);
  }, [accounts, id]);

  const account = accounts.find((a) => a.id === id);

  const load = useCallback(
    async (before?: number | null) => {
      if (!id) return;
      setError('');
      try {
        const page = await api.entries(token, id, before);
        setEntries((prev) => (before ? [...prev, ...page.entries] : page.entries));
        setNext(page.nextBefore);
      } catch (e) {
        setError((e as Error).message);
      }
    },
    [token, id],
  );

  useEffect(() => {
    setEntries([]);
    load();
  }, [load]);

  return (
    <section>
      <h2>Ledger entries</h2>
      <label>
        Account
        <select value={id} onChange={(e) => setId(e.target.value)}>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.id.slice(0, 8)} ({money(a.balanceMinor, a.currency)})
            </option>
          ))}
        </select>
      </label>
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Transaction</th>
            <th className="num">Change</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <tr key={e.entryId}>
              <td>{new Date(e.createdAt).toLocaleString()}</td>
              <td>
                <code title={e.transactionId}>{e.transactionId.slice(0, 8)}</code>
              </td>
              <td className={`num ${e.deltaMinor < 0 ? 'neg' : 'pos'}`}>
                {e.deltaMinor > 0 ? '+' : ''}
                {account ? money(e.deltaMinor, account.currency) : e.deltaMinor}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {entries.length === 0 && !error && <p className="muted">No entries yet.</p>}
      {next && <button onClick={() => load(next)}>Load more</button>}
      <p className="muted small">Every transaction writes one negative and one positive entry; together they always sum to zero.</p>
    </section>
  );
}
