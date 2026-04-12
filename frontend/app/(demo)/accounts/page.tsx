import HeaderBox from "@/components/HeaderBox"
import AccountCard from "@/components/accounts/AccountCard"
import DemoReseedCard from "@/components/demo/DemoReseedCard"
import { getDemoAccounts, type DemoAccount } from "@/lib/api"
import { resolveDemoSetupState } from "@/lib/demo-setup"

export const dynamic = "force-dynamic"

export default async function AccountsPage() {
  let accounts: DemoAccount[] = []
  let backendReachable = true
  const setupConfigured = Boolean(
    process.env.CORE_BANK_SETUP_USER && process.env.CORE_BANK_SETUP_PASS
  )

  try {
    accounts = await getDemoAccounts()
  } catch {
    backendReachable = false
  }

  const hasSeedData = accounts.length > 0
  const setupState = resolveDemoSetupState({
    backendReachable,
    setupConfigured,
    hasSeedData,
  })

  return (
    <section className="home">
      <div className="home-content">
        <HeaderBox
          type="title"
          title="Your Accounts"
          subtext="All accounts seeded from the CoreBank demo backend."
        />

        {hasSeedData ? (
          <div className="space-y-6">
            <DemoReseedCard
              setupState={setupState}
              hasSeedData={hasSeedData}
              defaultActivityAccountId={accounts[0]?.accountId ?? null}
            />
            <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-3">
              {accounts.map((account) => (
                <AccountCard key={account.accountId} account={account} />
              ))}
            </div>
          </div>
        ) : (
          <DemoReseedCard
            setupState={setupState}
            hasSeedData={hasSeedData}
            defaultActivityAccountId={null}
          />
        )}
      </div>
    </section>
  )
}
