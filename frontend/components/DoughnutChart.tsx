"use client"

import { Chart as ChartJS, ArcElement, Tooltip, Legend } from "chart.js"
import { Doughnut } from "react-chartjs-2"
import type { DemoAccount } from "@/lib/api"

ChartJS.register(ArcElement, Tooltip, Legend)

const CHART_COLORS = [
  "#6685CC",
  "#85A5FF",
  "#4F46E5",
  "#818CF8",
  "#22C55E",
]

interface DoughnutChartProps {
  accounts: DemoAccount[]
}

export default function DoughnutChart({ accounts }: DoughnutChartProps) {
  const labels = accounts.map((a) => a.accountNumber)
  const balances = accounts.map((a) => a.availableBalanceMinor)

  const data = {
    datasets: [
      {
        label: "Accounts",
        data: balances,
        backgroundColor: CHART_COLORS.slice(0, accounts.length),
      },
    ],
    labels,
  }

  return (
    <Doughnut
      data={data}
      options={{
        cutout: "60%",
        plugins: {
          legend: {
            display: false,
          },
        },
      }}
    />
  )
}
