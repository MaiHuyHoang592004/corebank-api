"use client"

import { formatCurrency, formatDateTime } from "@/lib/utils"
import type { DemoActivityItem } from "@/lib/api"
import {
  extractActivityAmount,
  parseActivityPayload,
  resolveActivityPresentation,
} from "@/lib/activity-presentation"

interface ActivityTableProps {
  items: DemoActivityItem[]
  accountId: string
}

export default function ActivityTable({ items, accountId }: ActivityTableProps) {
  if (items.length === 0) {
    return (
      <div className="rounded-xl border border-gray-200 bg-white p-8 text-center text-gray-500">
        No activity recorded.
      </div>
    )
  }

  return (
    <div className="rounded-xl border border-gray-200 bg-white overflow-hidden">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>
            <th className="px-4 py-3 text-left font-medium text-gray-600">Event</th>
            <th className="px-4 py-3 text-left font-medium text-gray-600">Date</th>
            <th className="px-4 py-3 text-left font-medium text-gray-600">Actor</th>
            <th className="px-4 py-3 text-right font-medium text-gray-600">Amount</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {items.map((item) => {
            const payload = parseActivityPayload(item.payloadJson)
            const presentation = resolveActivityPresentation(
              item.eventType,
              payload,
              accountId
            )
            const parsed = extractActivityAmount(payload)
            const amount = parsed?.amount ?? 0
            const amountDirection = presentation.amountDirection
            const amountClass =
              amountDirection === "debit"
                ? "text-red-600"
                : amountDirection === "credit"
                ? "text-green-600"
                : "text-gray-700"
            const signPrefix =
              amountDirection === "debit"
                ? "-"
                : amountDirection === "credit"
                ? "+"
                : ""

            return (
              <tr key={item.eventId} className="hover:bg-gray-50">
                <td className="px-4 py-3">
                  <p className="font-medium text-gray-800">{presentation.label}</p>
                  {presentation.impactHint && (
                    <p className="mt-1 text-xs text-gray-500">
                      {presentation.impactHint}
                    </p>
                  )}
                </td>
                <td className="px-4 py-3 text-gray-500">
                  {formatDateTime(item.occurredAt ?? null)}
                </td>
                <td className="px-4 py-3 text-gray-500">
                  {item.actor ?? "—"}
                </td>
                <td className="px-4 py-3 text-right font-semibold">
                  {parsed ? (
                    <span className={amountClass}>
                      {signPrefix}
                      {formatCurrency(amount, parsed.currency)}
                    </span>
                  ) : (
                    <span className="text-gray-400">—</span>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
