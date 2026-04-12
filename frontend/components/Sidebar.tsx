"use client"

import {
  LayoutDashboard,
  CreditCard,
  ArrowLeftRight,
  HandCoins,
  Bell,
} from "lucide-react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { cn } from "@/lib/utils"

const navLinks = [
  {
    href: "/dashboard",
    label: "Dashboard",
    icon: LayoutDashboard,
  },
  {
    href: "/accounts",
    label: "Accounts",
    icon: CreditCard,
  },
  {
    href: "/transfers/new",
    label: "Transfer",
    icon: ArrowLeftRight,
  },
  {
    href: "/payments",
    label: "Payments",
    icon: HandCoins,
  },
]

export default function Sidebar() {
  const pathname = usePathname()

  return (
    <section className="sidebar">
      <nav className="flex flex-col gap-4">
        <div className="mb-12 flex items-center gap-2">
          <div className="flex size-8 items-center justify-center rounded-lg bg-bank-gradient">
            <span className="text-sm font-bold text-white">CB</span>
          </div>
          <h1 className="sidebar-logo">CoreBank</h1>
        </div>

        {navLinks.map((item) => {
          const isActive =
            pathname === item.href || pathname.startsWith(`${item.href}/`)
          const Icon = item.icon

          return (
            <Link
              href={item.href}
              key={item.href}
              className={cn(
                "sidebar-link",
                isActive && "bg-bank-gradient"
              )}
            >
              <Icon className="size-6 text-gray-600" />
              <p
                className={cn("sidebar-label", isActive && "!text-white")}
              >
                {item.label}
              </p>
            </Link>
          )
        })}
      </nav>

      <div className="flex cursor-pointer items-center justify-between gap-2 py-6">
        <div className="footer_name">
          <p className="text-xl font-bold text-gray-700">DU</p>
        </div>
        <div className="footer_email">
          <p className="text-14 truncate font-semibold text-gray-700">
            Demo User
          </p>
          <p className="text-14 truncate font-normal text-gray-600">
            demo_user
          </p>
        </div>
        <Bell className="size-5 text-gray-400" />
      </div>
    </section>
  )
}
