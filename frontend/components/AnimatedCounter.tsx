"use client"

import CountUp from "react-countup"
import { useEffect, useState } from "react"
import { formatCurrency } from "@/lib/utils"

interface AnimatedCounterProps {
  amount: number
  currency?: string
}

export default function AnimatedCounter({
  amount,
  currency = "VND",
}: AnimatedCounterProps) {
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
  }, [])

  if (!mounted) {
    return (
      <span className="text-24 lg:text-30 font-semibold text-gray-900">
        {formatCurrency(amount, currency)}
      </span>
    )
  }

  return (
    <CountUp
      decimals={0}
      decimal=","
      end={amount}
      formattingFn={(value) => formatCurrency(Math.round(value), currency)}
      className="text-24 lg:text-30 font-semibold text-gray-900"
    />
  )
}
