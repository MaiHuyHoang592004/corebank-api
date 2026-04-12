import HeaderBox from "@/components/HeaderBox"
import InternalTransferForm from "@/components/transfers/InternalTransferForm"
import DemoReseedCard from "@/components/demo/DemoReseedCard"
import { getDemoAccounts, type DemoAccount } from "@/lib/api"
import { resolveDemoSetupState } from "@/lib/demo-setup"

export const dynamic = "force-dynamic"

export default async function TransferPage() {
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
          title="Internal Transfer"
          subtext="Transfer funds between your demo accounts via CoreBank's ledger."
        />

        {hasSeedData ? (
          <div className="space-y-6">
            <DemoReseedCard
              setupState={setupState}
              hasSeedData={hasSeedData}
              defaultActivityAccountId={accounts[0]?.accountId ?? null}
            />
            <div className="max-w-xl">
              <InternalTransferForm accounts={accounts} />
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
