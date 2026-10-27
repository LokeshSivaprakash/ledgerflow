export type Account = { id: string; currency: string; balanceMinor: number };
export type Transfer = {
  transactionId: string;
  fromAccountId: string;
  toAccountId: string;
  amountMinor: number;
  currency: string;
  fromBalanceMinor: number;
  createdAt: string;
};
export type Entry = { entryId: number; transactionId: string; deltaMinor: number; createdAt: string };
export type EntriesPage = { entries: Entry[]; nextBefore: number | null };
export type TransferResult = { status: number; replayed: boolean; transfer: Transfer };

/** A non-2xx response, carrying the stable `code` from the server's RFC 7807 body. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

async function call<T>(
  path: string,
  token: string | null,
  init: { method?: string; body?: unknown; headers?: Record<string, string> } = {},
): Promise<{ status: number; headers: Headers; data: T }> {
  const headers: Record<string, string> = { ...init.headers };
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(path, {
    method: init.method ?? 'GET',
    headers,
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const code = data?.code ?? (res.status === 401 ? 'UNAUTHORIZED' : 'ERROR');
    throw new ApiError(res.status, code, data?.detail ?? data?.title ?? `HTTP ${res.status}`);
  }
  return { status: res.status, headers: res.headers, data: data as T };
}

export const api = {
  login: async (subject: string) =>
    (await call<{ token: string }>('/api/dev/token', null, { method: 'POST', body: { subject } })).data.token,

  listAccounts: async (token: string) => (await call<Account[]>('/api/accounts', token)).data,

  openAccount: async (token: string, currency: string, initialDepositMinor: number) =>
    (await call<Account>('/api/accounts', token, { method: 'POST', body: { currency, initialDepositMinor } })).data,

  entries: async (token: string, accountId: string, before?: number | null) =>
    (await call<EntriesPage>(`/api/accounts/${accountId}/entries?limit=20${before ? `&before=${before}` : ''}`, token)).data,

  transfer: async (
    token: string,
    key: string,
    body: { fromAccountId: string; toAccountId: string; amountMinor: number; currency: string },
  ): Promise<TransferResult> => {
    const r = await call<Transfer>('/api/transfers', token, {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': key },
    });
    return { status: r.status, replayed: r.headers.get('Idempotent-Replayed') === 'true', transfer: r.data };
  },
};

export const money = (minor: number, currency: string) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(minor / 100);

/** "12.5" -> 1250. Returns null for anything that is not a positive amount with at most 2 decimals. */
export const toMinor = (text: string): number | null => {
  if (!/^\d+(\.\d{1,2})?$/.test(text.trim())) return null;
  const minor = Math.round(parseFloat(text) * 100);
  return minor > 0 ? minor : null;
};

export const newKey = () => `ui-${crypto.randomUUID()}`;
