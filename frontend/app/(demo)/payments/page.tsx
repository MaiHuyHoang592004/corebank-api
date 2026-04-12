import HeaderBox from "@/components/HeaderBox"
import HoldFlowPanel from "@/components/payments/HoldFlowPanel"
import DemoReseedCard from "@/components/demo/DemoReseedCard"
import { getDemoAccounts, type DemoAccount } from "@/lib/api"
import { resolveDemoSetupState } from "@/lib/demo-setup"

export const dynamic = "force-dynamic"

export default async function PaymentsPage() {
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
      <div className="home-content gap-8">
        <HeaderBox
          type="title"
          title="Payment Holds"
          subtext="Authorize holds, capture payments, and void holds — see available vs posted balance changes in real time."
        />

        <DemoReseedCard
          setupState={setupState}
          hasSeedData={hasSeedData}
          defaultActivityAccountId={accounts[0]?.accountId ?? null}
        />

        {!hasSeedData ? (
          <div className="rounded-xl border border-gray-200 bg-white p-8 text-center">
            <p className="text-16 font-semibold text-gray-700">Payments demo not ready</p>
            <p className="text-14 text-gray-500 mt-2">
              Use the demo setup card above to initialize or restore baseline data
              before running hold/capture/void demo flow.
            </p>
          </div>
        ) : (
          <div className="max-w-2xl">
            <HoldFlowPanel accounts={accounts} />
          </div>
        )}
      </div>
    </section>
  )
}
