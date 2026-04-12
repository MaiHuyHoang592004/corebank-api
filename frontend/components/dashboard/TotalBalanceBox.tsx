import AnimatedCounter from "@/components/AnimatedCounter"
import DoughnutChart from "@/components/DoughnutChart"
import type { DemoAccount } from "@/lib/api"

interface TotalBalanceBoxProps {
  accounts: DemoAccount[]
}

export default function TotalBalanceBox({ accounts }: TotalBalanceBoxProps) {
  const totalBalance = accounts.reduce(
    (sum, a) => sum + a.availableBalanceMinor,
    0
  )

  return (
    <section className="total-balance">
      <div className="total-balance-chart">
        <DoughnutChart accounts={accounts} />
      </div>

      <div className="flex flex-col gap-6">
        <h2 className="header-2">
          Accounts: {accounts.length}
        </h2>
        <div className="flex flex-col gap-2">
          <p className="total-balance-label">Total Available Balance</p>
          <div className="total-balance-amount flex-center gap-2">
            <AnimatedCounter amount={totalBalance} currency="VND" />
          </div>
        </div>
      </div>
    </section>
  )
}
