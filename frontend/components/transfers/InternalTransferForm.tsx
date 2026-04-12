"use client"

import Link from "next/link"
import { useState } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { z } from "zod"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type { DemoAccount, DemoTransferResponse } from "@/lib/api"
import { formatCurrency, maskAccountNumber } from "@/lib/utils"

const transferSchema = z.object({
  sourceAccountId: z.string().min(1, "Select a source account"),
  destinationAccountId: z.string().min(1, "Select a destination account"),
  amount: z.number().positive("Amount must be greater than 0").min(1, "Minimum transfer is 1 VND"),
  description: z.string().optional(),
})
type TransferFormData = z.infer<typeof transferSchema>

interface InternalTransferFormProps {
  accounts: DemoAccount[]
}

interface TransferActionImpactVm {
  journalId: string
  status: string
  sourceAccountId: string
  destinationAccountId: string
  amountMinor: number
  currency: string
  sourcePostedBeforeMinor: number | null
  sourcePostedAfterMinor: number | null
  sourceAvailableBeforeMinor: number | null
  sourceAvailableAfterMinor: number | null
  destinationPostedBeforeMinor: number | null
  destinationPostedAfterMinor: number | null
  destinationAvailableBeforeMinor: number | null
  destinationAvailableAfterMinor: number | null
}

function getDelta(before: number | null, after: number | null): number | null {
  if (before == null || after == null) return null
  return after - before
}

function formatDelta(
  before: number | null,
  after: number | null,
  currency: string
): { label: string; className: string } {
  const delta = getDelta(before, after)
  if (delta == null) {
    return {
      label: "—",
      className: "text-gray-400",
    }
  }

  if (delta === 0) {
    return {
      label: formatCurrency(0, currency),
      className: "text-gray-500",
    }
  }

  const sign = delta > 0 ? "+" : "-"
  return {
    label: `${sign}${formatCurrency(Math.abs(delta), currency)}`,
    className: delta > 0 ? "text-emerald-600" : "text-red-600",
  }
}

export default function InternalTransferForm({ accounts }: InternalTransferFormProps) {
  const [accountSnapshots, setAccountSnapshots] = useState<DemoAccount[]>(accounts)
  const [serverError, setServerError] = useState<string | null>(null)
  const [warning, setWarning] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [impact, setImpact] = useState<TransferActionImpactVm | null>(null)

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    reset,
    formState: { errors },
  } = useForm<TransferFormData>({
    resolver: zodResolver(transferSchema),
    defaultValues: {
      sourceAccountId: "",
      destinationAccountId: "",
      amount: 0,
      description: "",
    },
  })

  const sourceAccountId = watch("sourceAccountId")
  const destinationAccountId = watch("destinationAccountId")
  const amount = watch("amount")

  const sourceAccount = accountSnapshots.find((a) => a.accountId === sourceAccountId)

  async function refreshAccountSnapshot(accountId: string): Promise<DemoAccount> {
    const response = await fetch(`/api/demo/accounts/${accountId}`)
    const payload = await response.json()

    if (!response.ok) {
      throw new Error(payload.message ?? "Could not refresh account snapshot.")
    }

    return payload as DemoAccount
  }

  function mergeAccountSnapshot(next: DemoAccount) {
    setAccountSnapshots((prev) =>
      prev.map((account) =>
        account.accountId === next.accountId ? next : account
      )
    )
  }

  async function onSubmit(data: TransferFormData) {
    setServerError(null)
    setWarning(null)
    setImpact(null)
    setIsLoading(true)

    const sourceBefore =
      accountSnapshots.find((a) => a.accountId === data.sourceAccountId) ?? null
    const destinationBefore =
      accountSnapshots.find((a) => a.accountId === data.destinationAccountId) ?? null

    try {
      const response = await fetch("/api/demo/transfers/internal", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          sourceAccountId: data.sourceAccountId,
          destinationAccountId: data.destinationAccountId,
          amountMajor: data.amount,
          description: data.description ?? "",
        }),
      })

      const result = await response.json()

      if (!response.ok) {
        setServerError(result.message ?? "Transfer failed. Please try again.")
        return
      }

      const transferResult = result as DemoTransferResponse
      let sourceAfter: DemoAccount | null = null
      let destinationAfter: DemoAccount | null = null

      try {
        const refreshed = await Promise.all([
          refreshAccountSnapshot(data.sourceAccountId),
          refreshAccountSnapshot(data.destinationAccountId),
        ])
        sourceAfter = refreshed[0]
        destinationAfter = refreshed[1]
        mergeAccountSnapshot(sourceAfter)
        mergeAccountSnapshot(destinationAfter)
      } catch {
        setWarning(
          "Transfer completed, but account refresh failed. Showing balances from transfer response."
        )
        setAccountSnapshots((prev) =>
          prev.map((account) => {
            if (account.accountId === data.sourceAccountId) {
              return {
                ...account,
                postedBalanceMinor: transferResult.sourcePostedBalanceMinor,
                availableBalanceMinor:
                  transferResult.sourceAvailableBalanceAfterMinor,
              }
            }

            if (account.accountId === data.destinationAccountId) {
              return {
                ...account,
                postedBalanceMinor: transferResult.destinationPostedBalanceMinor,
                availableBalanceMinor:
                  transferResult.destinationAvailableBalanceAfterMinor,
              }
            }

            return account
          })
        )
      }

      setImpact({
        journalId: transferResult.journalId,
        status: transferResult.status,
        sourceAccountId: transferResult.sourceAccountId,
        destinationAccountId: transferResult.destinationAccountId,
        amountMinor: transferResult.amountMinor,
        currency: transferResult.currency,
        sourcePostedBeforeMinor: sourceBefore?.postedBalanceMinor ?? null,
        sourcePostedAfterMinor:
          sourceAfter?.postedBalanceMinor ??
          transferResult.sourcePostedBalanceMinor,
        sourceAvailableBeforeMinor: sourceBefore?.availableBalanceMinor ?? null,
        sourceAvailableAfterMinor:
          sourceAfter?.availableBalanceMinor ??
          transferResult.sourceAvailableBalanceAfterMinor,
        destinationPostedBeforeMinor: destinationBefore?.postedBalanceMinor ?? null,
        destinationPostedAfterMinor:
          destinationAfter?.postedBalanceMinor ??
          transferResult.destinationPostedBalanceMinor,
        destinationAvailableBeforeMinor:
          destinationBefore?.availableBalanceMinor ?? null,
        destinationAvailableAfterMinor:
          destinationAfter?.availableBalanceMinor ??
          transferResult.destinationAvailableBalanceAfterMinor,
      })

      reset({
        sourceAccountId: data.sourceAccountId,
        destinationAccountId: data.destinationAccountId,
        amount: 0,
        description: "",
      })
    } catch (err) {
      setServerError(
        err instanceof Error ? err.message : "Network error. Please try again."
      )
    } finally {
      setIsLoading(false)
    }
  }

  const sourceAccountContext = impact
    ? accountSnapshots.find((a) => a.accountId === impact.sourceAccountId)
    : null
  const destinationAccountContext = impact
    ? accountSnapshots.find((a) => a.accountId === impact.destinationAccountId)
    : null

  const sourceAvailableDelta = impact
    ? formatDelta(
        impact.sourceAvailableBeforeMinor,
        impact.sourceAvailableAfterMinor,
        impact.currency
      )
    : null
  const sourcePostedDelta = impact
    ? formatDelta(
        impact.sourcePostedBeforeMinor,
        impact.sourcePostedAfterMinor,
        impact.currency
      )
    : null
  const destinationAvailableDelta = impact
    ? formatDelta(
        impact.destinationAvailableBeforeMinor,
        impact.destinationAvailableAfterMinor,
        impact.currency
      )
    : null
  const destinationPostedDelta = impact
    ? formatDelta(
        impact.destinationPostedBeforeMinor,
        impact.destinationPostedAfterMinor,
        impact.currency
      )
    : null

  return (
    <div className="flex flex-col gap-6">
      {impact && (
        <div
          data-testid="transfer-impact-summary"
          className="rounded-xl border border-emerald-200 bg-emerald-50 p-4"
        >
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="text-sm font-semibold text-emerald-700">
                Transfer completed
              </p>
              <p className="mt-1 text-xs text-emerald-700/80">
                Status: <span className="font-semibold">{impact.status}</span> ·
                Journal:{" "}
                <span className="font-mono">
                  {impact.journalId.slice(0, 8)}...
                </span>
              </p>
              <p className="mt-1 text-xs text-emerald-700/80">
                Amount:{" "}
                <span className="font-semibold">
                  {formatCurrency(impact.amountMinor, impact.currency)}
                </span>
              </p>
            </div>

            <div className="flex flex-wrap gap-2">
              <Link
                href={`/accounts/${impact.sourceAccountId}`}
                className="rounded-md border border-emerald-300 px-2.5 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-100"
              >
                Open source activity
              </Link>
              <Link
                href={`/accounts/${impact.destinationAccountId}`}
                className="rounded-md border border-emerald-300 px-2.5 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-100"
              >
                Open destination activity
              </Link>
              <Link
                href="/dashboard"
                className="rounded-md border border-emerald-300 px-2.5 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-100"
              >
                Open dashboard outcomes
              </Link>
            </div>
          </div>

          <div className="mt-3 grid gap-3 rounded-lg bg-white p-3 text-xs md:grid-cols-2">
            <div>
              <p className="text-gray-500">
                Source account (
                {sourceAccountContext
                  ? maskAccountNumber(sourceAccountContext.accountNumber)
                  : `${impact.sourceAccountId.slice(0, 8)}...`}
                ) available
              </p>
              <p className="font-medium text-gray-700">
                {impact.sourceAvailableBeforeMinor == null
                  ? "—"
                  : formatCurrency(
                      impact.sourceAvailableBeforeMinor,
                      impact.currency
                    )}
                {" -> "}
                {impact.sourceAvailableAfterMinor == null
                  ? "—"
                  : formatCurrency(
                      impact.sourceAvailableAfterMinor,
                      impact.currency
                    )}
              </p>
              <p
                className={`font-semibold ${sourceAvailableDelta?.className ?? "text-gray-400"}`}
              >
                Delta: {sourceAvailableDelta?.label ?? "—"}
              </p>
            </div>
            <div>
              <p className="text-gray-500">
                Source account (
                {sourceAccountContext
                  ? maskAccountNumber(sourceAccountContext.accountNumber)
                  : `${impact.sourceAccountId.slice(0, 8)}...`}
                ) posted
              </p>
              <p className="font-medium text-gray-700">
                {impact.sourcePostedBeforeMinor == null
                  ? "—"
                  : formatCurrency(impact.sourcePostedBeforeMinor, impact.currency)}
                {" -> "}
                {impact.sourcePostedAfterMinor == null
                  ? "—"
                  : formatCurrency(impact.sourcePostedAfterMinor, impact.currency)}
              </p>
              <p
                className={`font-semibold ${sourcePostedDelta?.className ?? "text-gray-400"}`}
              >
                Delta: {sourcePostedDelta?.label ?? "—"}
              </p>
            </div>
            <div>
              <p className="text-gray-500">
                Destination account (
                {destinationAccountContext
                  ? maskAccountNumber(destinationAccountContext.accountNumber)
                  : `${impact.destinationAccountId.slice(0, 8)}...`}
                ) available
              </p>
              <p className="font-medium text-gray-700">
                {impact.destinationAvailableBeforeMinor == null
                  ? "—"
                  : formatCurrency(
                      impact.destinationAvailableBeforeMinor,
                      impact.currency
                    )}
                {" -> "}
                {impact.destinationAvailableAfterMinor == null
                  ? "—"
                  : formatCurrency(
                      impact.destinationAvailableAfterMinor,
                      impact.currency
                    )}
              </p>
              <p
                className={`font-semibold ${destinationAvailableDelta?.className ?? "text-gray-400"}`}
              >
                Delta: {destinationAvailableDelta?.label ?? "—"}
              </p>
            </div>
            <div>
              <p className="text-gray-500">
                Destination account (
                {destinationAccountContext
                  ? maskAccountNumber(destinationAccountContext.accountNumber)
                  : `${impact.destinationAccountId.slice(0, 8)}...`}
                ) posted
              </p>
              <p className="font-medium text-gray-700">
                {impact.destinationPostedBeforeMinor == null
                  ? "—"
                  : formatCurrency(
                      impact.destinationPostedBeforeMinor,
                      impact.currency
                    )}
                {" -> "}
                {impact.destinationPostedAfterMinor == null
                  ? "—"
                  : formatCurrency(
                      impact.destinationPostedAfterMinor,
                      impact.currency
                    )}
              </p>
              <p
                className={`font-semibold ${destinationPostedDelta?.className ?? "text-gray-400"}`}
              >
                Delta: {destinationPostedDelta?.label ?? "—"}
              </p>
            </div>
          </div>
        </div>
      )}

      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-6">
      {/* Source account */}
      <div className="flex flex-col gap-2">
        <Label htmlFor="source">From Account</Label>
        <Select
          value={sourceAccountId}
          onValueChange={(val) => setValue("sourceAccountId", val)}
        >
          <SelectTrigger id="source">
            <SelectValue placeholder="Select source account" />
          </SelectTrigger>
          <SelectContent>
            {accountSnapshots.map((account) => (
              <SelectItem key={account.accountId} value={account.accountId}>
                {account.accountNumber} — {account.productName} (
                {formatCurrency(account.availableBalanceMinor, account.currency)}
                )
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {errors.sourceAccountId && (
          <p className="text-sm text-red-500">{errors.sourceAccountId.message}</p>
        )}
      </div>

      {/* Destination account */}
      <div className="flex flex-col gap-2">
        <Label htmlFor="destination">To Account</Label>
        <Select
          value={destinationAccountId}
          onValueChange={(val) => setValue("destinationAccountId", val)}
        >
          <SelectTrigger id="destination">
            <SelectValue placeholder="Select destination account" />
          </SelectTrigger>
          <SelectContent>
            {accountSnapshots
              .filter((a) => a.accountId !== sourceAccountId)
              .map((account) => (
                <SelectItem key={account.accountId} value={account.accountId}>
                  {account.accountNumber} — {account.productName}
                </SelectItem>
              ))}
          </SelectContent>
        </Select>
        {errors.destinationAccountId && (
          <p className="text-sm text-red-500">
            {errors.destinationAccountId.message}
          </p>
        )}
      </div>

      {/* Amount */}
      <div className="flex flex-col gap-2">
        <Label htmlFor="amount">Amount (VND)</Label>
        <Input
          id="amount"
          type="number"
          min={1}
          placeholder="Enter amount in VND"
          {...register("amount", { valueAsNumber: true })}
        />
        {errors.amount && (
          <p className="text-sm text-red-500">{errors.amount.message}</p>
        )}
        {sourceAccount && amount > sourceAccount.availableBalanceMinor && (
          <p className="text-sm text-red-500">
            Insufficient available balance (
            {formatCurrency(
              sourceAccount.availableBalanceMinor,
              sourceAccount.currency
            )}
            )
          </p>
        )}
      </div>

      {/* Description */}
      <div className="flex flex-col gap-2">
        <Label htmlFor="description">Description (optional)</Label>
        <Input
          id="description"
          placeholder="e.g. Internal transfer for demo"
          {...register("description")}
        />
      </div>

      {/* Server error */}
      {serverError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          {serverError}
        </div>
      )}

      {warning && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-700">
          {warning}
        </div>
      )}

      {/* Submit */}
      <Button
        type="submit"
        disabled={isLoading}
        className="w-full"
      >
        {isLoading ? "Processing..." : "Transfer"}
      </Button>
      </form>
    </div>
  )
}
