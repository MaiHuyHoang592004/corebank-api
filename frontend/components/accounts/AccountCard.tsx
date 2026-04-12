import Link from "next/link"
import { formatCurrency, maskAccountNumber } from "@/lib/utils"
import type { DemoAccount } from "@/lib/api"

const PRODUCT_TYPE_COLORS: Record<string, string> = {
  CHECKING: "from-blue-500 to-indigo-500",
  SAVINGS: "from-green-500 to-emerald-500",
  TERM_DEPOSIT: "from-amber-500 to-orange-500",
  LOAN: "from-red-500 to-pink-500",
  DEFAULT: "from-gray-500 to-slate-600",
}

interface AccountCardProps {
  account: DemoAccount
}

export default function AccountCard({ account }: AccountCardProps) {
  const colorClass =
    PRODUCT_TYPE_COLORS[account.productType] ??
    PRODUCT_TYPE_COLORS.DEFAULT

  return (
    <Link href={`/accounts/${account.accountId}`} className="block">
      <div
        className={`relative flex h-[190px] w-full max-w-[360px] flex-col justify-between rounded-[20px] border border-white bg-gradient-to-br ${colorClass} p-6 shadow-creditCard transition-transform hover:scale-[1.02]`}
      >
        <div className="flex items-center justify-between">
          <div>
            <p className="text-12 font-medium text-white/80">
              {account.productName}
            </p>
            <p className="text-14 font-semibold text-white">
              {account.accountNumber}
            </p>
          </div>
          <div className="rounded-full border border-white/30 bg-white/10 px-3 py-1">
            <p className="text-10 font-semibold text-white uppercase">
              {account.status}
            </p>
          </div>
        </div>

        <div className="flex flex-col gap-1">
          <p className="text-12 text-white/70">Available Balance</p>
          <p className="text-24 font-bold font-ibm-plex-serif text-white">
            {formatCurrency(account.availableBalanceMinor, account.currency)}
          </p>
          <p className="text-12 text-white/70">
            {account.customerName}
          </p>
        </div>
      </div>

      <div className="mt-2 flex items-center justify-between rounded-lg border border-gray-200 bg-white px-4 py-3">
        <div>
          <p className="text-12 text-gray-500">Posted Balance</p>
          <p className="text-14 font-semibold text-gray-700">
            {formatCurrency(account.postedBalanceMinor, account.currency)}
          </p>
        </div>
        <div className="text-right">
          <p className="text-12 text-gray-500">Account</p>
          <p className="text-14 font-medium text-gray-600">
            {maskAccountNumber(account.accountNumber)}
          </p>
        </div>
      </div>
    </Link>
  )
}
