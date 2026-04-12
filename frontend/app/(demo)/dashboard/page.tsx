import HeaderBox from "@/components/HeaderBox"
import RecentActivity from "@/components/activity/RecentActivity"
import OutcomeHighlightsCard from "@/components/dashboard/OutcomeHighlightsCard"
import TotalBalanceBox from "@/components/dashboard/TotalBalanceBox"
import DemoReseedCard from "@/components/demo/DemoReseedCard"
import { getDemoAccounts, getAccountActivity, type DemoAccount } from "@/lib/api"
import {
  aggregateDashboardActivity,
  computeOutcomeHighlights,
  createAccountNumberLookup,
  type DashboardActivityItemVm,
  type DashboardOutcomeHighlightsVm,
} from "@/lib/dashboard-activity"
import { resolveDemoSetupState } from "@/lib/demo-setup"
import Link from "next/link"

export const dynamic = "force-dynamic"

export default async function DashboardPage() {
  let accounts: DemoAccount[] = []
  let recentActivity: DashboardActivityItemVm[] = []
  let outcomeHighlights: DashboardOutcomeHighlightsVm = {
    authorizedCount: 0,
    capturedCount: 0,
    voidedCount: 0,
    transferCount: 0,
  }
  let backendReachable = true
  const setupConfigured = Boolean(
    process.env.CORE_BANK_SETUP_USER && process.env.CORE_BANK_SETUP_PASS
  )

  try {
    accounts = await getDemoAccounts()
    if (accounts.length > 0) {
      const byAccount = await Promise.allSettled(
        accounts.map(async (account) => {
          const activityData = await getAccountActivity(account.accountId, 0, 5)
          return {
            accountId: account.accountId,
            items: activityData.items,
          }
        })
      )

      const fulfilled = byAccount.flatMap((result) =>
        result.status === "fulfilled" ? [result.value] : []
      )

      recentActivity = aggregateDashboardActivity(fulfilled, 10)
      outcomeHighlights = computeOutcomeHighlights(recentActivity)
    }
  } catch {
    backendReachable = false
  }

  const hasSeedData = accounts.length > 0
  const accountNumberById = createAccountNumberLookup(accounts)
  const setupState = resolveDemoSetupState({
    backendReachable,
    setupConfigured,
    hasSeedData,
  })

  return (
    <section className="home">
      <div className="home-content">
        <header className="home-header">
          <HeaderBox
            type="greeting"
            title="Welcome"
            user="Demo User"
            subtext="CoreBank demo portal — showcasing backend banking capabilities."
          />

          {hasSeedData && (
            <TotalBalanceBox accounts={accounts} />
          )}
        </header>

        {hasSeedData ? (
          <div className="grid gap-4 xl:grid-cols-2">
            <div className="rounded-xl border border-gray-200 bg-white p-5">
              <p className="text-16 font-semibold text-gray-900">Demo launchpad</p>
              <p className="mt-1 text-14 text-gray-600">
                Recommended sequence for a fast, repeatable demo run.
              </p>

              <ol className="mt-3 list-decimal space-y-1 pl-5 text-13 text-gray-700">
                <li>Re-seed baseline demo data when needed.</li>
                <li>Review accounts and available vs posted balances.</li>
                <li>Run an internal transfer.</li>
                <li>Run payment hold → capture/void flow.</li>
              </ol>

              <div className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-3">
                <Link
                  href="/accounts"
                  className="rounded-lg border border-gray-200 px-3 py-2 text-center text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  Accounts
                </Link>
                <Link
                  href="/transfers/new"
                  className="rounded-lg border border-gray-200 px-3 py-2 text-center text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  Transfer
                </Link>
                <Link
                  href="/payments"
                  className="rounded-lg border border-gray-200 px-3 py-2 text-center text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  Payments
                </Link>
              </div>
            </div>

            <DemoReseedCard
              setupState={setupState}
              hasSeedData={hasSeedData}
              defaultActivityAccountId={accounts[0]?.accountId ?? null}
            />
          </div>
        ) : (
          <DemoReseedCard
            setupState={setupState}
            hasSeedData={hasSeedData}
            defaultActivityAccountId={null}
          />
        )}

        {hasSeedData && (
          <div className="mt-4">
            <OutcomeHighlightsCard highlights={outcomeHighlights} />
          </div>
        )}

        {hasSeedData && recentActivity.length > 0 && (
          <RecentActivity
            items={recentActivity}
            accountNumberById={accountNumberById}
          />
        )}
      </div>
    </section>
  )
}
