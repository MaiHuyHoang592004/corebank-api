import { NextResponse } from "next/server";

const BACKEND_URL = process.env.CORE_BANK_URL ?? "http://localhost:9090";
const SETUP_USER = process.env.CORE_BANK_SETUP_USER;
const SETUP_PASS = process.env.CORE_BANK_SETUP_PASS;

function basicAuthHeader(username: string, password: string): string {
  const encoded = Buffer.from(`${username}:${password}`).toString("base64");
  return `Basic ${encoded}`;
}

function extractMessage(payload: unknown): string | null {
  if (payload && typeof payload === "object" && "message" in payload) {
    const message = (payload as { message?: unknown }).message;
    return typeof message === "string" ? message : null;
  }
  return null;
}

function normalizeBackendError(status: number, message: string | null): {
  status: number;
  message: string;
} {
  if (status === 401 || status === 403) {
    return {
      status: 403,
      message: "Demo setup requires OPS or ADMIN access.",
    };
  }

  if (status === 503) {
    return {
      status: 503,
      message: message ?? "Backend demo setup service is currently unavailable.",
    };
  }

  if (status >= 500) {
    return {
      status: 502,
      message: "Could not complete demo setup because backend is unavailable.",
    };
  }

  return {
    status: 400,
    message: message ?? "Backend rejected demo setup request.",
  };
}

export async function POST() {
  if (!SETUP_USER || !SETUP_PASS) {
    return NextResponse.json(
      {
        message:
          "Setup credentials are not configured for this frontend. Configure CORE_BANK_SETUP_USER and CORE_BANK_SETUP_PASS.",
      },
      { status: 503 }
    );
  }

  try {
    const backendResponse = await fetch(`${BACKEND_URL}/api/demo/setup`, {
      method: "POST",
      headers: {
        Authorization: basicAuthHeader(SETUP_USER, SETUP_PASS),
        "Content-Type": "application/json",
      },
      cache: "no-store",
    });

    const rawBody = await backendResponse.text();
    let payload: unknown = null;

    if (rawBody) {
      try {
        payload = JSON.parse(rawBody);
      } catch {
        payload = { message: rawBody };
      }
    }

    if (!backendResponse.ok) {
      const normalized = normalizeBackendError(
        backendResponse.status,
        extractMessage(payload)
      );

      return NextResponse.json({
        message: normalized.message,
        backendStatus: backendResponse.status,
      }, { status: normalized.status });
    }

    return NextResponse.json(
      payload && typeof payload === "object"
        ? payload
        : { message: "Demo data initialized successfully." },
      { status: backendResponse.status }
    );
  } catch {
    return NextResponse.json(
      {
        message: "Could not complete demo setup because backend is unreachable.",
      },
      { status: 502 }
    );
  }
}
