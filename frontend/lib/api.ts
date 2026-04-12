/**
 * Server-side CoreBank API client.
 * NEVER import this from client components.
 * Only used by Next.js route handlers under app/api/.
 */

const BACKEND_URL = process.env.CORE_BANK_URL ?? "http://localhost:9090";
const DEMO_USERNAME = process.env.CORE_BANK_USER ?? "demo_user";
const DEMO_PASSWORD = process.env.CORE_BANK_PASS ?? "demo_user";

function basicAuthHeader(): string {
  const encoded = Buffer.from(`${DEMO_USERNAME}:${DEMO_PASSWORD}`).toString(
    "base64"
  );
  return `Basic ${encoded}`;
}

async function request<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const url = `${BACKEND_URL}${path}`;
  const response = await fetch(url, {
    ...options,
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      Authorization: basicAuthHeader(),
      ...options.headers,
    },
  });

  if (!response.ok) {
    let message = `HTTP ${response.status}: ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.message) message = body.message;
    } catch {
      // ignore JSON parse errors
    }
    throw new Error(message);
  }

  return response.json() as Promise<T>;
}

// ---------------------------------------------------------------------------
// Demo accounts
// ---------------------------------------------------------------------------

export interface DemoAccount {
  accountId: string;
  customerId: string;
  customerName: string;
  productId: string;
  productCode: string;
  productName: string;
  productType: string;
  accountNumber: string;
  currency: string;
  status: string;
  postedBalanceMinor: number;
  availableBalanceMinor: number;
}

export async function getDemoAccounts(): Promise<DemoAccount[]> {
  const data = await request<{ accounts: DemoAccount[] }>("/api/demo/accounts");
  return data.accounts;
}

export async function getDemoAccount(
  accountId: string
): Promise<DemoAccount> {
  return request<DemoAccount>(`/api/demo/accounts/${accountId}`);
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

export interface DemoActivityItem {
  eventId: string;
  eventType: string;
  occurredAt: string | null;
  actor: string | null;
  payloadJson: string | null;
}

export interface DemoActivityPage {
  page: number;
  size: number;
  totalItems: number;
  items: DemoActivityItem[];
}

export async function getAccountActivity(
  accountId: string,
  page: number = 0,
  size: number = 20
): Promise<DemoActivityPage> {
  return request<DemoActivityPage>(
    `/api/demo/accounts/${accountId}/activity?page=${page}&size=${size}`
  );
}

// ---------------------------------------------------------------------------
// Account lookup
// ---------------------------------------------------------------------------

export interface DemoLookupItem {
  accountId: string;
  accountNumber: string;
  customerName: string;
  productName: string;
  productType: string;
}

export async function lookupAccounts(
  query: string
): Promise<DemoLookupItem[]> {
  const params = new URLSearchParams({ query });
  return request<DemoLookupItem[]>(`/api/demo/accounts/lookup?${params}`);
}

// ---------------------------------------------------------------------------
// Internal transfer
// ---------------------------------------------------------------------------

export interface DemoTransferRequest {
  sourceAccountId: string;
  destinationAccountId: string;
  amountMajor: number;
  description: string;
}

export interface DemoTransferResponse {
  journalId: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amountMinor: number;
  currency: string;
  sourcePostedBalanceMinor: number;
  sourceAvailableBalanceAfterMinor: number;
  destinationPostedBalanceMinor: number;
  destinationAvailableBalanceAfterMinor: number;
  status: string;
  message: string;
}

export async function postInternalTransfer(
  req: DemoTransferRequest
): Promise<DemoTransferResponse> {
  return request<DemoTransferResponse>("/api/demo/transfers/internal", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// ---------------------------------------------------------------------------
// Payment holds
// ---------------------------------------------------------------------------

export interface DemoHold {
  holdId: string;
  paymentOrderId: string;
  payerAccountId: string;
  payeeAccountId: string | null;
  amountMinor: number;
  remainingMinor: number;
  holdStatus: string;
  paymentStatus: string;
  currency: string;
  paymentType: string;
  createdAt: string | null;
}

export interface DemoHoldPage {
  page: number;
  size: number;
  totalItems: number;
  items: DemoHold[];
}

export interface DemoAuthorizeRequest {
  payerAccountId: string;
  payeeAccountId: string;
  amountMajor: number;
  paymentType: string;
  description: string;
}

export interface DemoAuthorizeResponse {
  paymentOrderId: string;
  holdId: string;
  payerAccountId: string;
  postedBalanceMinor: number;
  availableBalanceBeforeMinor: number;
  availableBalanceAfterMinor: number;
  holdAmountMinor: number;
  currency: string;
  status: string;
}

export interface DemoCaptureRequest {
  holdId: string;
  amountMajor: number;
  description: string;
}

export interface DemoCaptureResponse {
  paymentOrderId: string;
  holdId: string;
  journalId: string;
  capturedAmountMinor: number;
  remainingMinor: number;
  holdStatus: string;
  paymentStatus: string;
  currency: string;
}

export interface DemoVoidRequest {
  holdId: string;
  description: string;
}

export interface DemoVoidResponse {
  paymentOrderId: string;
  holdId: string;
  restoredAmountMinor: number;
  availableBalanceBeforeMinor: number;
  availableBalanceAfterMinor: number;
  currency: string;
  status: string;
}

export async function getAccountHolds(
  accountId: string,
  page: number = 0,
  size: number = 20
): Promise<DemoHoldPage> {
  return request<DemoHoldPage>(
    `/api/demo/payments/accounts/${accountId}/holds?page=${page}&size=${size}`
  );
}

export async function postAuthorizeHold(
  req: DemoAuthorizeRequest
): Promise<DemoAuthorizeResponse> {
  return request<DemoAuthorizeResponse>("/api/demo/payments/authorize", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function postCaptureHold(
  req: DemoCaptureRequest
): Promise<DemoCaptureResponse> {
  return request<DemoCaptureResponse>("/api/demo/payments/capture", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export async function postVoidHold(
  req: DemoVoidRequest
): Promise<DemoVoidResponse> {
  return request<DemoVoidResponse>("/api/demo/payments/void", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

