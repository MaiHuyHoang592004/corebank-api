"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { formatCurrency, formatDateTime } from "@/lib/utils";
import {
  type DemoSetupState,
  parseSetupResultSummary,
  type SetupResultSummary,
  shortenId,
} from "@/lib/demo-setup";

interface DemoReseedCardProps {
  setupState: DemoSetupState;
  hasSeedData: boolean;
  defaultActivityAccountId?: string | null;
}

interface SetupApiError {
  message?: string;
}

function normalizeErrorMessage(status: number, message?: string): string {
  if (status === 401 || status === 403) {
    return "Demo setup requires OPS or ADMIN access.";
  }

  if (status === 503) {
    return (
      message ??
      "Setup credentials are not configured for this frontend. Configure CORE_BANK_SETUP_USER and CORE_BANK_SETUP_PASS."
    );
  }

  if (status >= 500) {
    return "Could not complete demo setup because backend is unavailable.";
  }

  return message ?? "Could not complete demo setup. Please try again.";
}

function getSetupStateContent(setupState: DemoSetupState, hasSeedData: boolean) {
  if (setupState === "backend_unreachable") {
    return {
      title: "Backend unreachable",
      description:
        "CoreBank backend is not reachable. Start backend on port 9090 to enable setup and demo flows.",
      actionLabel: null,
    };
  }

  if (setupState === "setup_creds_missing") {
    return {
      title: "Setup credentials missing",
      description: hasSeedData
        ? "Demo data is available, but re-seed is disabled because privileged setup credentials are not configured."
        : "Setup credentials are not configured, so this frontend cannot initialize demo baseline data.",
      actionLabel: null,
    };
  }

  if (setupState === "seed_required") {
    return {
      title: "Demo data not seeded",
      description:
        "Backend is reachable but no demo accounts are available yet. Initialize demo data to start deterministic demo flows.",
      actionLabel: "Initialize demo data",
    };
  }

  return {
    title: "Demo data ready",
    description:
      "Re-seed demo baseline before presenting hold/capture/void so balances and activity stay deterministic.",
    actionLabel: "Re-seed demo data",
  };
}

export default function DemoReseedCard({
  setupState,
  hasSeedData,
  defaultActivityAccountId = null,
}: DemoReseedCardProps) {
  const router = useRouter();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [summary, setSummary] = useState<SetupResultSummary | null>(null);

  const stateContent = getSetupStateContent(setupState, hasSeedData);
  const canRunSetup = Boolean(stateContent.actionLabel);
  const activityAccountId = summary?.sourceAccountId ?? defaultActivityAccountId;

  async function handleSetupAction() {
    if (!canRunSetup) {
      return;
    }

    setIsLoading(true);
    setError(null);
    setSuccess(null);
    setSummary(null);

    try {
      const response = await fetch("/api/demo/setup", {
        method: "POST",
      });

      let payload: unknown = null;
      try {
        payload = await response.json();
      } catch {
        payload = null;
      }

      if (!response.ok) {
        setError(
          normalizeErrorMessage(
            response.status,
            (payload as SetupApiError | null)?.message
          )
        );
        return;
      }

      setSummary(parseSetupResultSummary(payload));
      setSuccess(
        setupState === "seed_required"
          ? "Demo data initialized to baseline. Refreshing page..."
          : "Demo data re-seeded to baseline. Refreshing page..."
      );

      setTimeout(() => {
        router.refresh();
      }, 2200);
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Network error while running demo setup."
      );
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5">
      <p className="text-16 font-semibold text-gray-900">Demo setup status</p>
      <p className="mt-1 text-14 text-gray-600">{stateContent.description}</p>

      <div className="mt-3 rounded-lg bg-gray-50 px-3 py-2">
        <p className="text-xs font-medium uppercase tracking-wide text-gray-500">
          State: {setupState}
        </p>
        <p className="mt-1 text-sm font-medium text-gray-700">{stateContent.title}</p>
      </div>

      {canRunSetup && (
        <div className="mt-4">
          <Button type="button" onClick={handleSetupAction} disabled={isLoading}>
            {isLoading
              ? setupState === "seed_required"
                ? "Initializing demo data..."
                : "Re-seeding demo data..."
              : stateContent.actionLabel}
          </Button>
        </div>
      )}

      {success && (
        <div className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
          <p>{success}</p>
          {summary && (
            <div className="mt-3 grid gap-2 text-xs text-emerald-800 md:grid-cols-2">
              <p>
                Baseline initialized at:{" "}
                <span className="font-medium">
                  {formatDateTime(summary.initializedAt)}
                </span>
              </p>
              <p>
                Suggested payment amount:{" "}
                <span className="font-medium">
                  {summary.paymentAmountMinor == null
                    ? "—"
                    : formatCurrency(summary.paymentAmountMinor, "VND")}
                </span>
              </p>
              <p>
                Source account:{" "}
                <span className="font-mono font-medium">
                  {shortenId(summary.sourceAccountId)}
                </span>
              </p>
              <p>
                Destination account:{" "}
                <span className="font-mono font-medium">
                  {shortenId(summary.destinationAccountId)}
                </span>
              </p>
            </div>
          )}

          <div className="mt-3 flex flex-wrap gap-2">
            <Link
              href="/payments"
              className="rounded-md border border-emerald-300 px-2.5 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-100"
            >
              Open Payments
            </Link>
            <Link
              href="/accounts"
              className="rounded-md border border-emerald-300 px-2.5 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-100"
            >
              Open Accounts
            </Link>
            {activityAccountId && (
              <Link
                href={`/accounts/${activityAccountId}`}
                className="rounded-md border border-emerald-300 px-2.5 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-100"
              >
                Open Activity
              </Link>
            )}
          </div>
        </div>
      )}

      {error && (
        <p className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </p>
      )}
    </div>
  );
}
