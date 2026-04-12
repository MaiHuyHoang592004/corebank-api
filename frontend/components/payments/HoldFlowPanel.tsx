"use client"

import Link from "next/link"
import { useCallback, useEffect, useState } from "react"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"
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
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { z } from "zod"
import { formatCurrency } from "@/lib/utils"
import HoldCard, { type HoldActionSuccess } from "./HoldCard"
import type { DemoAccount, DemoAuthorizeResponse, DemoHold } from "@/lib/api"

const PAYMENT_TYPES = ["INTERNAL", "EXTERNAL", "POS", "ATM", "WIRE"] as const

const authorizeSchema = z.object({
  payerAccountId: z.string().min(1, "Select a source account"),
  payeeAccountId: z.string().min(1, "Select a destination account"),
  amount: z.number().positive("Amount must be greater than 0").min(1, "Minimum is 1 VND"),
  paymentType: z.string().min(1, "Select a payment type"),
  description: z.string().optional(),
})
type AuthorizeFormData = z.infer<typeof authorizeSchema>

interface HoldFlowPanelProps {
  accounts: DemoAccount[]
}

interface PaymentActionImpact {
  action: "AUTHORIZE" | "CAPTURE" | "VOID"
  payerAccountId: string
  holdId: string
  paymentOrderId: string
  holdStatus: string
  paymentStatus: string
  amountMinor: number
  currency: string
  availableBeforeMinor: number | null
  availableAfterMinor: number | null
  postedBeforeMinor: number | null
  postedAfterMinor: number | null
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

export default function HoldFlowPanel({ accounts }: HoldFlowPanelProps) {
  const [accountSnapshots, setAccountSnapshots] = useState<DemoAccount[]>(accounts)
  const [selectedAccountId, setSelectedAccountId] = useState(
    accounts[0]?.accountId ?? ""
  )
  const [activeTab, setActiveTab] = useState<"authorize" | "holds">("authorize")
  const [serverError, setServerError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [activeHolds, setActiveHolds] = useState<DemoHold[]>([])
  const [holdsLoading, setHoldsLoading] = useState(false)
  const [holdsError, setHoldsError] = useState<string | null>(null)
  const [impact, setImpact] = useState<PaymentActionImpact | null>(null)

  const selectedAccount = accountSnapshots.find((a) => a.accountId === selectedAccountId)

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    reset,
    formState: { errors },
  } = useForm<AuthorizeFormData>({
    resolver: zodResolver(authorizeSchema),
    defaultValues: {
      payerAccountId: selectedAccountId,
      payeeAccountId: "",
      amount: 0,
      paymentType: "INTERNAL",
      description: "",
    },
  })

  useEffect(() => {
    if (!selectedAccountId) return
    setValue("payerAccountId", selectedAccountId, { shouldValidate: true })
  }, [selectedAccountId, setValue])

  const payerAccountId = watch("payerAccountId")
  const amount = watch("amount")
  const payeeAccountId = watch("payeeAccountId")
  const paymentType = watch("paymentType")

  const payerAccount = accountSnapshots.find((a) => a.accountId === payerAccountId)
  const requestedAmountMinor = amount || 0
  const insufficientFunds =
    payerAccount && requestedAmountMinor > payerAccount.availableBalanceMinor

  const mergeAccountSnapshot = useCallback((next: DemoAccount) => {
    setAccountSnapshots((prev) => {
      const index = prev.findIndex((item) => item.accountId === next.accountId)
      if (index === -1) {
        return [...prev, next]
      }
      const copy = [...prev]
      copy[index] = next
      return copy
    })
  }, [])

  const refreshAccountSnapshot = useCallback(async (accountId: string) => {
    const response = await fetch(`/api/demo/accounts/${accountId}`)
    const payload = await response.json()
    if (!response.ok) {
      throw new Error(payload.message ?? "Failed to refresh account snapshot")
    }
    const next = payload as DemoAccount
    mergeAccountSnapshot(next)
    return next
  }, [mergeAccountSnapshot])

  const loadHolds = useCallback(async (accountId: string) => {
    if (!accountId) {
      setActiveHolds([])
      return
    }
    setHoldsLoading(true)
    setHoldsError(null)
    try {
      const res = await fetch(
        `/api/demo/payments/accounts/${accountId}/holds?page=0&size=50`
      )
      if (!res.ok) {
        const data = await res.json()
        setHoldsError(data.message ?? "Failed to load holds")
        setActiveHolds([])
        return
      }
      const data = await res.json()
      setActiveHolds(data.items ?? [])
    } catch {
      setHoldsError("Network error. Could not load holds.")
      setActiveHolds([])
    } finally {
      setHoldsLoading(false)
    }
  }, [])

  useEffect(() => {
    if (!selectedAccountId) return
    void loadHolds(selectedAccountId)
  }, [selectedAccountId, loadHolds])

  const summarizeImpact = useCallback(async ({
    action,
    payerAccountId: impactedAccountId,
    holdId,
    paymentOrderId,
    holdStatus,
    paymentStatus,
    amountMinor,
    currency,
    availableBeforeMinor,
    availableAfterMinor,
  }: HoldActionSuccess | {
    action: "AUTHORIZE"
    payerAccountId: string
    holdId: string
    paymentOrderId: string
    holdStatus: string
    paymentStatus: string
    amountMinor: number
    currency: string
    availableBeforeMinor?: number
    availableAfterMinor?: number
  }) => {
    const before = accountSnapshots.find((a) => a.accountId === impactedAccountId) ?? null
    const refreshed = await refreshAccountSnapshot(impactedAccountId)
    await loadHolds(impactedAccountId)

    setSelectedAccountId(impactedAccountId)
    setActiveTab("holds")
    setImpact({
      action,
      payerAccountId: impactedAccountId,
      holdId,
      paymentOrderId,
      holdStatus,
      paymentStatus,
      amountMinor,
      currency,
      availableBeforeMinor: availableBeforeMinor ?? before?.availableBalanceMinor ?? null,
      availableAfterMinor: availableAfterMinor ?? refreshed.availableBalanceMinor ?? null,
      postedBeforeMinor: before?.postedBalanceMinor ?? null,
      postedAfterMinor: refreshed.postedBalanceMinor ?? null,
    })
  }, [accountSnapshots, loadHolds, refreshAccountSnapshot])

  async function onAuthorize(data: AuthorizeFormData) {
    setServerError(null)
    setImpact(null)
    setIsLoading(true)

    try {
      const res = await fetch("/api/demo/payments/authorize", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          payerAccountId: data.payerAccountId,
          payeeAccountId: data.payeeAccountId,
          amountMajor: data.amount,
          paymentType: data.paymentType,
          description: data.description ?? "Demo hold",
        }),
      })
      const result = await res.json()

      if (!res.ok) {
        setServerError(result.message ?? "Hold authorization failed. Please try again.")
        return
      }

      const typedResult = result as DemoAuthorizeResponse
      await summarizeImpact({
        action: "AUTHORIZE",
        payerAccountId: data.payerAccountId,
        holdId: typedResult.holdId,
        paymentOrderId: typedResult.paymentOrderId,
        holdStatus: "AUTHORIZED",
        paymentStatus: typedResult.status,
        amountMinor: typedResult.holdAmountMinor,
        currency: typedResult.currency,
        availableBeforeMinor: typedResult.availableBalanceBeforeMinor,
        availableAfterMinor: typedResult.availableBalanceAfterMinor,
      })

      reset({
        payerAccountId: data.payerAccountId,
        payeeAccountId: "",
        amount: 0,
        paymentType: "INTERNAL",
        description: "",
      })
    } catch {
      setServerError("Network error. Please try again.")
    } finally {
      setIsLoading(false)
    }
  }

  async function handleHoldActionSuccess(event: HoldActionSuccess) {
    setServerError(null)
    await summarizeImpact(event)
  }

  const availableDelta = impact
    ? formatDelta(impact.availableBeforeMinor, impact.availableAfterMinor, impact.currency)
    : null
  const postedDelta = impact
    ? formatDelta(impact.postedBeforeMinor, impact.postedAfterMinor, impact.currency)
    : null

  return (
    <div className="space-y-4">
      {/* Account selector */}
      <div className="rounded-xl border border-gray-200 bg-white p-4">
        <Label className="mb-2 block text-sm font-medium text-gray-700">
          Select Account
        </Label>
        <Select
          value={selectedAccountId}
          onValueChange={(value) => {
            setSelectedAccountId(value)
            setValue("payerAccountId", value, { shouldValidate: true })
          }}
        >
          <SelectTrigger>
            <SelectValue placeholder="Choose account" />
          </SelectTrigger>
          <SelectContent>
            {accountSnapshots.map((acc) => (
              <SelectItem key={acc.accountId} value={acc.accountId}>
                {acc.accountNumber} — {acc.productName} (
                {formatCurrency(acc.availableBalanceMinor, acc.currency)} avail.)
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {/* Balance display */}
        {selectedAccount && (
          <div className="mt-3 grid grid-cols-2 gap-3 rounded-lg bg-gray-50 p-3">
            <div>
              <p className="text-xs text-gray-500">Available Balance</p>
              <p className="text-sm font-bold text-gray-900">
                {formatCurrency(selectedAccount.availableBalanceMinor, selectedAccount.currency)}
              </p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Posted Balance</p>
              <p className="text-sm font-bold text-gray-700">
                {formatCurrency(selectedAccount.postedBalanceMinor, selectedAccount.currency)}
              </p>
            </div>
          </div>
        )}
      </div>

      {impact && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="text-sm font-semibold text-emerald-700">
                {impact.action === "AUTHORIZE"
                  ? "Hold authorized"
                  : impact.action === "CAPTURE"
                  ? "Hold captured"
                  : "Hold voided"}
              </p>
              <p className="mt-1 text-xs text-emerald-700/80">
                Hold <span className="font-mono">{impact.holdId.slice(0, 8)}...</span> ·
                Hold status: <span className="font-semibold">{impact.holdStatus}</span> ·
                Payment status: <span className="font-semibold">{impact.paymentStatus}</span>
              </p>
            </div>
            <Link
              href={`/accounts/${impact.payerAccountId}`}
              className="rounded-md border border-emerald-300 px-3 py-1.5 text-xs font-medium text-emerald-700 hover:bg-emerald-100"
            >
              Open account activity
            </Link>
          </div>

          <div className="mt-3 grid gap-3 rounded-lg bg-white p-3 text-xs md:grid-cols-2">
            <div>
              <p className="text-gray-500">Action amount</p>
              <p className="font-semibold text-gray-800">
                {formatCurrency(impact.amountMinor, impact.currency)}
              </p>
            </div>
            <div>
              <p className="text-gray-500">Payment order</p>
              <p className="font-mono text-gray-700">{impact.paymentOrderId.slice(0, 8)}...</p>
            </div>
            <div>
              <p className="text-gray-500">Available balance (before → after)</p>
              <p className="font-medium text-gray-700">
                {impact.availableBeforeMinor == null
                  ? "—"
                  : formatCurrency(impact.availableBeforeMinor, impact.currency)}
                {" -> "}
                {impact.availableAfterMinor == null
                  ? "—"
                  : formatCurrency(impact.availableAfterMinor, impact.currency)}
              </p>
              <p className={`font-semibold ${availableDelta?.className ?? "text-gray-400"}`}>
                Delta: {availableDelta?.label ?? "—"}
              </p>
            </div>
            <div>
              <p className="text-gray-500">Posted balance (before → after)</p>
              <p className="font-medium text-gray-700">
                {impact.postedBeforeMinor == null
                  ? "—"
                  : formatCurrency(impact.postedBeforeMinor, impact.currency)}
                {" -> "}
                {impact.postedAfterMinor == null
                  ? "—"
                  : formatCurrency(impact.postedAfterMinor, impact.currency)}
              </p>
              <p className={`font-semibold ${postedDelta?.className ?? "text-gray-400"}`}>
                Delta: {postedDelta?.label ?? "—"}
              </p>
            </div>
          </div>
        </div>
      )}

      <Tabs
        value={activeTab}
        onValueChange={(value) => {
          const next = value as "authorize" | "holds"
          setActiveTab(next)
          if (next === "holds" && selectedAccountId) {
            void loadHolds(selectedAccountId)
          }
        }}
        className="w-full"
      >
        <TabsList className="w-full">
          <TabsTrigger value="authorize" className="flex-1">
            Authorize Hold
          </TabsTrigger>
          <TabsTrigger value="holds" className="flex-1">
            Active Holds
          </TabsTrigger>
        </TabsList>

        {/* Authorize tab */}
        <TabsContent value="authorize" className="mt-4">
          <div className="rounded-xl border border-gray-200 bg-white p-5">
            <p className="mb-4 text-sm text-gray-600">
              Place a hold on funds in an account. The available balance decreases immediately;
              the posted balance is unchanged until the hold is captured or voided.
            </p>
            <div className="mb-4 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-700">
              Suggested demo sequence: <span className="font-semibold">Authorize</span> a hold,
              then <span className="font-semibold">Capture</span> or <span className="font-semibold">Void</span>
              it from the Active Holds tab to highlight available vs posted semantics.
            </div>

            <form onSubmit={handleSubmit(onAuthorize)} className="flex flex-col gap-5">
              {/* Payer account */}
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="payer">From Account (payer)</Label>
                <Select
                  value={payerAccountId}
                  onValueChange={(val) => {
                    setValue("payerAccountId", val, { shouldValidate: true })
                    setSelectedAccountId(val)
                  }}
                >
                  <SelectTrigger id="payer">
                    <SelectValue placeholder="Select source account" />
                  </SelectTrigger>
                  <SelectContent>
                    {accountSnapshots.map((acc) => (
                      <SelectItem key={acc.accountId} value={acc.accountId}>
                        {acc.accountNumber} — {acc.productName}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {errors.payerAccountId && (
                  <p className="text-xs text-red-500">{errors.payerAccountId.message}</p>
                )}
              </div>

              {/* Payee account */}
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="payee">To Account (payee)</Label>
                <Select
                  value={payeeAccountId}
                  onValueChange={(val) => setValue("payeeAccountId", val, { shouldValidate: true })}
                >
                  <SelectTrigger id="payee">
                    <SelectValue placeholder="Select destination account" />
                  </SelectTrigger>
                  <SelectContent>
                    {accountSnapshots
                      .filter((a) => a.accountId !== payerAccountId)
                      .map((acc) => (
                        <SelectItem key={acc.accountId} value={acc.accountId}>
                          {acc.accountNumber} — {acc.productName}
                        </SelectItem>
                      ))}
                  </SelectContent>
                </Select>
                {errors.payeeAccountId && (
                  <p className="text-xs text-red-500">{errors.payeeAccountId.message}</p>
                )}
              </div>

              {/* Amount */}
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="amount">Amount (VND)</Label>
                <Input
                  id="amount"
                  type="number"
                  min={1}
                  placeholder="Enter hold amount in VND"
                  {...register("amount", { valueAsNumber: true })}
                />
                {errors.amount && (
                  <p className="text-xs text-red-500">{errors.amount.message}</p>
                )}
                {insufficientFunds && (
                  <p className="text-xs text-red-500">
                    Insufficient available balance (
                    {formatCurrency(payerAccount!.availableBalanceMinor, payerAccount!.currency)})
                  </p>
                )}
              </div>

              {/* Payment type */}
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="type">Payment Type</Label>
                <Select
                  value={paymentType}
                  onValueChange={(val) => setValue("paymentType", val)}
                >
                  <SelectTrigger id="type">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {PAYMENT_TYPES.map((t) => (
                      <SelectItem key={t} value={t}>
                        {t.charAt(0) + t.slice(1).toLowerCase()}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {errors.paymentType && (
                  <p className="text-xs text-red-500">{errors.paymentType.message}</p>
                )}
              </div>

              {/* Description */}
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="desc">Description (optional)</Label>
                <Input
                  id="desc"
                  placeholder="e.g. Demo hold for payment"
                  {...register("description")}
                />
              </div>

              {/* Server error */}
              {serverError && (
                <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
                  {serverError}
                </div>
              )}

              <Button type="submit" disabled={isLoading} className="w-full">
                {isLoading ? "Authorizing..." : "Authorize Hold"}
              </Button>
            </form>
          </div>
        </TabsContent>

        {/* Active Holds tab */}
        <TabsContent value="holds" className="mt-4">
          {holdsLoading ? (
            <div className="rounded-xl border border-gray-200 bg-white p-8 text-center text-gray-500">
              Loading holds...
            </div>
          ) : holdsError ? (
            <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700">
              {holdsError}
              <button
                onClick={() => {
                  void loadHolds(selectedAccountId)
                }}
                className="ml-3 underline"
              >
                Retry
              </button>
            </div>
          ) : activeHolds.length === 0 ? (
            <div className="rounded-xl border border-gray-200 bg-white p-8 text-center">
              <p className="text-sm font-medium text-gray-600">No active holds</p>
              <p className="mt-1 text-xs text-gray-400">
                Authorize a hold first to see it here.
              </p>
            </div>
          ) : (
            <div className="space-y-3">
              {activeHolds.map((hold) => (
                <HoldCard
                  key={hold.holdId}
                  hold={hold}
                  onActionSuccess={handleHoldActionSuccess}
                />
              ))}
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
