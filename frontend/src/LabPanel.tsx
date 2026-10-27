import { useState } from 'react';
import { ApiError, api, money, newKey } from './api';

type Outcome = { label: string; ok: boolean; detail: string };
type Report = { title: string; lines: string[]; checks: Outcome[] };

async function fresh(token: string, depositMinor: number) {
  const a = await api.openAccount(token, 'USD', depositMinor);
  const b = await api.openAccount(token, 'USD', 0);
  return { a, b };
}

async function fire(n: number, run: (i: number) => Promise<string>): Promise<string[]> {
  return Promise.all(
    Array.from({ length: n }, (_, i) =>
      run(i).catch((e) => (e instanceof ApiError ? `${e.status} ${e.code}` : `network ${(e as Error).message}`)),
    ),
  );
}

const tally = (codes: string[]) => {
  const m = new Map<string, number>();
  codes.forEach((c) => m.set(c, (m.get(c) ?? 0) + 1));
  return [...m.entries()].map(([k, v]) => `${v} x ${k}`).join(', ');
};

export default function LabPanel({ token, onDone }: { token: string; onDone: () => void }) {
  const [report, setReport] = useState<Report | null>(null);
  const [busy, setBusy] = useState(false);

  const execute = async (job: () => Promise<Report>) => {
    setBusy(true);
    setReport(null);
    try {
      setReport(await job());
    } catch (e) {
      setReport({ title: 'Lab failed', lines: [(e as Error).message], checks: [] });
    } finally {
      setBusy(false);
      onDone();
    }
  };

  const overdraftRace = () =>
    execute(async () => {
      const { a, b } = await fresh(token, 10_000); // 100.00
      const codes = await fire(20, async () => {
        const r = await api.transfer(token, newKey(), { fromAccountId: a.id, toAccountId: b.id, amountMinor: 1_000, currency: 'USD' });
        return String(r.status);
      });
      const all = await api.listAccounts(token);
      const fa = all.find((x) => x.id === a.id)!;
      const fb = all.find((x) => x.id === b.id)!;
      const ok = codes.filter((c) => c === '201').length;
      const rejected = codes.filter((c) => c === '422 INSUFFICIENT_FUNDS').length;
      return {
        title: '20 parallel transfers of 10.00 from an account holding 100.00',
        lines: [`Responses: ${tally(codes)}`, `Source ends at ${money(fa.balanceMinor, 'USD')}, destination at ${money(fb.balanceMinor, 'USD')}`],
        checks: [
          { label: 'Exactly 10 succeeded', ok: ok === 10, detail: `${ok} succeeded` },
          { label: 'Exactly 10 rejected for insufficient funds', ok: rejected === 10, detail: `${rejected} rejected` },
          { label: 'Source balance is 0.00 (never negative)', ok: fa.balanceMinor === 0, detail: money(fa.balanceMinor, 'USD') },
          { label: 'Money is conserved (source + destination = 100.00)', ok: fa.balanceMinor + fb.balanceMinor === 10_000, detail: money(fa.balanceMinor + fb.balanceMinor, 'USD') },
        ],
      };
    });

  const retryStorm = () =>
    execute(async () => {
      const { a, b } = await fresh(token, 10_000);
      const key = newKey();
      const codes = await fire(10, async () => {
        const r = await api.transfer(token, key, { fromAccountId: a.id, toAccountId: b.id, amountMinor: 2_500, currency: 'USD' });
        return r.replayed ? '200 replayed' : String(r.status);
      });
      const all = await api.listAccounts(token);
      const fa = all.find((x) => x.id === a.id)!;
      const created = codes.filter((c) => c === '201').length;
      const replayed = codes.filter((c) => c === '200 replayed').length;
      return {
        title: '10 parallel requests, same Idempotency-Key, 25.00 each',
        lines: [`Responses: ${tally(codes)}`, `Source ends at ${money(fa.balanceMinor, 'USD')}`],
        checks: [
          { label: 'Exactly one request created the transaction', ok: created === 1, detail: `${created} created` },
          { label: 'The other nine got the stored response back', ok: replayed === 9, detail: `${replayed} replayed` },
          { label: 'Money moved once: source is 75.00', ok: fa.balanceMinor === 7_500, detail: money(fa.balanceMinor, 'USD') },
        ],
      };
    });

  return (
    <section>
      <h2>Concurrency lab</h2>
      <p className="muted">
        Each experiment opens two fresh accounts, fires requests in parallel from your browser, and checks the guarantees. A browser limits parallel connections
        per host, so for true parallelism run <code>scripts/demo.sh</code>.
      </p>
      <div className="row">
        <button disabled={busy} onClick={overdraftRace}>
          Overdraft race
        </button>
        <button disabled={busy} onClick={retryStorm}>
          Retry storm
        </button>
      </div>
      {busy && <p className="muted">Running...</p>}
      {report && (
        <div className="result">
          <strong>{report.title}</strong>
          {report.lines.map((l) => (
            <div key={l}>{l}</div>
          ))}
          <ul className="checks">
            {report.checks.map((c) => (
              <li key={c.label} className={c.ok ? 'pass' : 'fail'}>
                {c.ok ? 'PASS' : 'FAIL'} {c.label} <span className="muted">({c.detail})</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
