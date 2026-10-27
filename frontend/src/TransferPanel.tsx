import { useEffect, useState } from 'react';
import { Account, ApiError, TransferResult, api, money, newKey, toMinor } from './api';

export default function TransferPanel({ token, accounts, onDone }: { token: string; accounts: Account[]; onDone: () => void }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('10.00');
  const [key, setKey] = useState(newKey);
  const [result, setResult] = useState<TransferResult | null>(null);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!from && accounts.length) setFrom(accounts[0].id);
  }, [accounts, from]);

  const source = accounts.find((a) => a.id === from);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setResult(null);
    setError(null);
    const minor = toMinor(amount);
    if (!source) return setError(new Error('Pick a source account.'));
    if (minor === null) return setError(new Error('Amount must be positive with at most 2 decimals.'));
    setBusy(true);
    try {
      setResult(await api.transfer(token, key, { fromAccountId: from, toAccountId: to.trim(), amountMinor: minor, currency: source.currency }));
      onDone();
    } catch (err) {
      setError(err as Error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section>
      <h2>Send money</h2>
      <form onSubmit={submit} className="stack">
        <label>
          From
          <select value={from} onChange={(e) => setFrom(e.target.value)}>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.id.slice(0, 8)} ({money(a.balanceMinor, a.currency)})
              </option>
            ))}
          </select>
        </label>
        <label>
          To (account id; pick one of yours or paste someone else's)
          <input list="own-accounts" value={to} onChange={(e) => setTo(e.target.value)} placeholder="account id" />
          <datalist id="own-accounts">
            {accounts.filter((a) => a.id !== from).map((a) => (
              <option key={a.id} value={a.id}>
                {money(a.balanceMinor, a.currency)}
              </option>
            ))}
          </datalist>
        </label>
        <label>
          Amount {source ? `(${source.currency})` : ''}
          <input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" />
        </label>
        <label>
          Idempotency-Key
          <div className="row">
            <input value={key} onChange={(e) => setKey(e.target.value)} />
            <button type="button" onClick={() => setKey(newKey())}>
              New key
            </button>
          </div>
        </label>
        <button disabled={busy || !to.trim()}>{busy ? 'Sending...' : 'Send'}</button>
      </form>
      <p className="muted small">
        The key stays the same after a send. Press Send again to see a safe replay (nothing moves twice). Change the amount without changing the key to see the
        server reject the reuse.
      </p>

      {result && (
        <div className={`result ${result.replayed ? 'replay' : 'ok'}`}>
          <strong>
            {result.status} {result.replayed ? 'Replayed: same transaction returned, no new money moved' : 'Created'}
          </strong>
          <div>
            Transaction <code>{result.transfer.transactionId}</code>
          </div>
          <div>Balance after: {money(result.transfer.fromBalanceMinor, result.transfer.currency)}</div>
        </div>
      )}
      {error && (
        <div className="result bad">
          <strong>
            {error instanceof ApiError ? `${error.status} ${error.code}` : 'Error'}
          </strong>
          <div>{error.message}</div>
        </div>
      )}
    </section>
  );
}
