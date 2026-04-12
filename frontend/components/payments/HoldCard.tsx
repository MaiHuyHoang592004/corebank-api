"use client"

import { useState } from "react"
import { Button } from "@/components/ui/button"
import { cn, formatCurrency, formatDateTime } from "@/lib/utils"
import type { DemoCaptureResponse, DemoHold, DemoVoidResponse } from "@/lib/api"

export interface HoldActionSuccess {
  action: "CAPTURE" | "VOID"
  payerAccountId: string
  holdId: string
  paymentOrderId: string
  amountMinor: number
  currency: string
  holdStatus: string
  paymentStatus: string
  availableBeforeMinor?: number
  availableAfterMinor?: number
}

interface HoldCardProps {
  hold: DemoHold
  onActionSuccess: (event: HoldActionSuccess) => Promise<void> | void
}

function extractErrorMessage(payload: unknown): string | null {
  if (!payload || typeof payload !== "object") return null
  if (!("message" in payload)) return null
  const message = (payload as { message?: unknown }).message
  return typeof message === "string" ? message : null
}

const STATUS_COLORS: Record<string, string> = {
  AUTHORIZED: "bg-emerald-100 text-emerald-700",
  PARTIALLY_CAPTURED: "bg-amber-100 text-amber-700",
  CAPTURED: "bg-gray-100 text-gray-500",
  VOIDED: "bg-red-50 text-red-400",
}

const PAYMENT_TYPE_LABELS: Record<string, string> = {
  INTERNAL: "Internal",
  EXTERNAL: "External",
  POS: "POS",
  ATM: "ATM",
  WIRE: "Wire",
}

export default function HoldCard({ hold, onActionSuccess }: HoldCardProps) {
  const [isCapturing, setIsCapturing] = useState(false)
  const [isVoiding, setIsVoiding] = useState(false)
  const [captureAmount, setCaptureAmount] = useState("")
  const [serverError, setServerError] = useState<string | null>(null)
  const [showCaptureForm, setShowCaptureForm] = useState(false)

  const statusColor = STATUS_COLORS[hold.holdStatus] ?? "bg-gray-100 text-gray-600"
  const canCapture = hold.holdStatus === "AUTHORIZED" || hold.holdStatus === "PARTIALLY_CAPTURED"
  const canVoid = hold.holdStatus === "AUTHORIZED"

  async function handleCapture() {
    setServerError(null)
    const amount = parseInt(captureAmount, 10)
    if (!amount || amount <= 0 || amount > hold.remainingMinor) {
      setServerError(
        `Amount must be between 1 and remaining ${formatCurrency(hold.remainingMinor, hold.currency)}`
      )
      return
    }

    setIsCapturing(true)
    try {
      const res = await fetch("/api/demo/payments/capture", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          holdId: hold.holdId,
          amountMajor: amount,
          description: "Demo capture",
        }),
      })
      const data = (await res.json()) as unknown
      if (!res.ok) {
        setServerError(extractErrorMessage(data) ?? "Capture failed")
      } else {
        const result = data as DemoCaptureResponse
        setShowCaptureForm(false)
        setCaptureAmount("")
        await onActionSuccess({
          action: "CAPTURE",
          payerAccountId: hold.payerAccountId,
          holdId: result.holdId,
          paymentOrderId: result.paymentOrderId,
          amountMinor: result.capturedAmountMinor,
          currency: result.currency,
          holdStatus: result.holdStatus,
          paymentStatus: result.paymentStatus,
        })
      }
    } catch {
      setServerError("Network error. Please try again.")
    } finally {
      setIsCapturing(false)
    }
  }

  async function handleVoid() {
    if (!confirm("Release this hold and restore the available balance?")) return
    setServerError(null)
    setIsVoiding(true)
    try {
      const res = await fetch("/api/demo/payments/void", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          holdId: hold.holdId,
          description: "Demo void",
        }),
      })
      const data = (await res.json()) as unknown
      if (!res.ok) {
        setServerError(extractErrorMessage(data) ?? "Void failed")
      } else {
        const result = data as DemoVoidResponse
        await onActionSuccess({
          action: "VOID",
          payerAccountId: hold.payerAccountId,
          holdId: result.holdId,
          paymentOrderId: result.paymentOrderId,
          amountMinor: result.restoredAmountMinor,
          currency: result.currency,
          holdStatus: result.status,
          paymentStatus: result.status,
          availableBeforeMinor: result.availableBalanceBeforeMinor,
          availableAfterMinor: result.availableBalanceAfterMinor,
        })
      }
    } catch {
      setServerError("Network error. Please try again.")
    } finally {
      setIsVoiding(false)
    }
  }

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className={cn("rounded-full px-2.5 py-0.5 text-xs font-semibold uppercase", statusColor)}>
              {hold.holdStatus.replace("_", " ")}
            </span>
            <span className="text-xs text-gray-400">
              {PAYMENT_TYPE_LABELS[hold.paymentType] ?? hold.paymentType}
            </span>
          </div>
          <p className="mt-1.5 text-xs font-mono text-gray-500 break-all">
            Hold ID: {hold.holdId.slice(0, 8)}...
          </p>
        </div>
        <p className="text-right font-semibold text-gray-900 whitespace-nowrap">
          {formatCurrency(hold.amountMinor, hold.currency)}
        </p>
      </div>

      {/* Balance impact */}
      <div className="mt-3 grid grid-cols-2 gap-3 rounded-lg bg-gray-50 p-3 text-xs">
        <div>
          <p className="text-gray-500">Original hold</p>
          <p className="font-semibold text-gray-700">{formatCurrency(hold.amountMinor, hold.currency)}</p>
        </div>
        <div>
          <p className="text-gray-500">Remaining</p>
          <p className={cn("font-semibold", hold.remainingMinor === 0 ? "text-gray-400" : "text-amber-600")}>
            {formatCurrency(hold.remainingMinor, hold.currency)}
          </p>
        </div>
        <div className="col-span-2">
          <p className="text-gray-500">Created</p>
          <p className="font-medium text-gray-700">{formatDateTime(hold.createdAt)}</p>
        </div>
      </div>

      {/* Capture form inline */}
      {showCaptureForm && (
        <div className="mt-3 rounded-lg border border-blue-200 bg-blue-50 p-3">
          <p className="mb-2 text-xs font-semibold text-blue-700">
            Capture (remaining: {formatCurrency(hold.remainingMinor, hold.currency)})
          </p>
          <div className="flex gap-2">
            <input
              type="number"
              className="flex-1 rounded border border-gray-300 px-2 py-1 text-sm"
              placeholder="Amount (VND)"
              value={captureAmount}
              onChange={(e) => setCaptureAmount(e.target.value)}
              min={1}
              max={hold.remainingMinor}
            />
            <Button
              size="sm"
              onClick={handleCapture}
              disabled={isCapturing}
              className="bg-emerald-600 hover:bg-emerald-700 text-xs"
            >
              {isCapturing ? "Capturing..." : "Confirm"}
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => { setShowCaptureForm(false); setCaptureAmount(""); setServerError(null); }}
              className="text-xs"
            >
              Cancel
            </Button>
          </div>
        </div>
      )}

      {/* Server error */}
      {serverError && (
        <p className="mt-2 text-xs text-red-600">{serverError}</p>
      )}

      {/* Actions */}
      {canCapture && !showCaptureForm && (
        <div className="mt-3 flex gap-2">
          <Button
            size="sm"
            onClick={() => {
              setCaptureAmount(String(hold.remainingMinor))
              setShowCaptureForm(true)
            }}
            className="flex-1 bg-emerald-600 hover:bg-emerald-700 text-xs"
          >
            Capture
          </Button>
          {canVoid && (
            <Button
              size="sm"
              variant="outline"
              onClick={handleVoid}
              disabled={isVoiding}
              className="flex-1 text-xs text-red-600 border-red-200 hover:bg-red-50"
            >
              {isVoiding ? "Voiding..." : "Void"}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
