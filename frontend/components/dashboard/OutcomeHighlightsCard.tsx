import Link from "next/link";
import type { DashboardOutcomeHighlightsVm } from "@/lib/dashboard-activity";

interface OutcomeHighlightsCardProps {
  highlights: DashboardOutcomeHighlightsVm;
}

export default function OutcomeHighlightsCard({
  highlights,
}: OutcomeHighlightsCardProps) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5">
      <p className="text-16 font-semibold text-gray-900">Outcome highlights</p>
      <p className="mt-1 text-14 text-gray-600">
        Recent outcomes across all demo accounts.
      </p>

      <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
          <p className="text-xs text-gray-500">Hold Authorized</p>
          <p className="text-lg font-semibold text-blue-700">
            {highlights.authorizedCount}
          </p>
        </div>
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
          <p className="text-xs text-gray-500">Hold Captured</p>
          <p className="text-lg font-semibold text-indigo-700">
            {highlights.capturedCount}
          </p>
        </div>
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
          <p className="text-xs text-gray-500">Hold Voided</p>
          <p className="text-lg font-semibold text-gray-700">
            {highlights.voidedCount}
          </p>
        </div>
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-3">
          <p className="text-xs text-gray-500">Transfers</p>
          <p className="text-lg font-semibold text-emerald-700">
            {highlights.transferCount}
          </p>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-3">
        <Link
          href="/payments"
          className="rounded-lg border border-gray-200 px-3 py-2 text-center text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          Open Payments
        </Link>
        <Link
          href="/transfers/new"
          className="rounded-lg border border-gray-200 px-3 py-2 text-center text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          Open Transfers
        </Link>
        <Link
          href="/accounts"
          className="rounded-lg border border-gray-200 px-3 py-2 text-center text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          Open Accounts
        </Link>
      </div>
    </div>
  );
}
