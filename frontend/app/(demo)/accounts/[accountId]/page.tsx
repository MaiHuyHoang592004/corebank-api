import HeaderBox from "@/components/HeaderBox"
import ActivityTable from "@/components/activity/ActivityTable"
import Pagination from "@/components/Pagination"
import { getDemoAccount, getAccountActivity, type DemoAccount, type DemoActivityItem } from "@/lib/api"
import { normalizeUiPage, toApiPage } from "@/lib/activity-pagination"
import { formatCurrency } from "@/lib/utils"

export const dynamic = "force-dynamic"

interface Props {
  params: { accountId: string }
  searchParams: { page?: string }
}

const PAGE_SIZE = 20

export default async function AccountDetailPage({ params, searchParams }: Props) {
  const page = normalizeUiPage(searchParams.page)
  const apiPage = toApiPage(page)

  let account: DemoAccount | null = null
  let activity: DemoActivityItem[] = []
  let totalItems = 0

  try {
    account = await getDemoAccount(params.accountId)
    const activityData = await getAccountActivity(params.accountId, apiPage, PAGE_SIZE)
    activity = activityData.items
    totalItems = activityData.totalItems
  } catch {
    return (
      <section className="home">
        <div className="home-content">
          <HeaderBox
            type="title"
            title="Account Not Found"
            subtext="The backend may not be running or the account does not exist."
          />
        </div>
      </section>
    )
  }

  if (!account) {
    return (
      <section className="home">
        <div className="home-content">
          <HeaderBox
            type="title"
            title="Account Not Found"
            subtext="The backend may not be running or the account does not exist."
          />
        </div>
      </section>
    )
  }

  const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE))

  return (
    <section className="home">
      <div className="home-content gap-8">
        <HeaderBox
          type="title"
          title={account.productName}
          subtext={`Account: ${account.accountNumber}`}
        />

        {/* Balance summary */}
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <div className="rounded-xl border border-gray-200 bg-white p-6">
            <p className="text-12 font-medium text-gray-500">Available Balance</p>
            <p className="text-20 font-bold font-ibm-plex-serif text-gray-900 mt-1">
              {formatCurrency(account.availableBalanceMinor, account.currency)}
            </p>
          </div>
          <div className="rounded-xl border border-gray-200 bg-white p-6">
            <p className="text-12 font-medium text-gray-500">Posted Balance</p>
            <p className="text-20 font-bold font-ibm-plex-serif text-gray-900 mt-1">
              {formatCurrency(account.postedBalanceMinor, account.currency)}
            </p>
          </div>
          <div className="rounded-xl border border-gray-200 bg-white p-6">
            <p className="text-12 font-medium text-gray-500">Status</p>
            <p className="text-20 font-bold text-gray-900 mt-1">
              {account.status}
            </p>
          </div>
        </div>

        {/* Account details */}
        <div className="rounded-xl border border-gray-200 bg-white p-6">
          <p className="text-16 font-semibold text-gray-900 mb-4">Account Details</p>
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <p className="text-gray-500">Account Number</p>
              <p className="font-medium text-gray-800">{account.accountNumber}</p>
            </div>
            <div>
              <p className="text-gray-500">Product</p>
              <p className="font-medium text-gray-800">{account.productName}</p>
            </div>
            <div>
              <p className="text-gray-500">Type</p>
              <p className="font-medium text-gray-800">{account.productType}</p>
            </div>
            <div>
              <p className="text-gray-500">Currency</p>
              <p className="font-medium text-gray-800">{account.currency}</p>
            </div>
            <div>
              <p className="text-gray-500">Customer</p>
              <p className="font-medium text-gray-800">{account.customerName}</p>
            </div>
            <div>
              <p className="text-gray-500">Account ID</p>
              <p className="font-medium text-gray-800 text-xs break-all">{account.accountId}</p>
            </div>
          </div>
        </div>

        {/* Activity */}
        {totalItems > 0 && (
          <div>
            <p className="mb-2 text-sm text-gray-500">
              {totalItems} event{totalItems !== 1 ? "s" : ""} total
            </p>
            <ActivityTable items={activity} accountId={params.accountId} />
            <Pagination page={page} totalPages={totalPages} />
          </div>
        )}

        {totalItems === 0 && <ActivityTable items={activity} accountId={params.accountId} />}
      </div>
    </section>
  )
}
