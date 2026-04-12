"use client"

import { Menu } from "lucide-react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTrigger,
} from "@/components/ui/sheet"
import { cn } from "@/lib/utils"
import { LayoutDashboard, CreditCard, ArrowLeftRight, HandCoins } from "lucide-react"

const navLinks = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "/accounts", label: "Accounts", icon: CreditCard },
  { href: "/transfers/new", label: "Transfer", icon: ArrowLeftRight },
  { href: "/payments", label: "Payments", icon: HandCoins },
]

export default function MobileNav() {
  const pathname = usePathname()

  return (
    <section className="w-full max-w-[264px]">
      <Sheet>
        <SheetTrigger asChild>
          <button type="button" aria-label="Open menu">
            <Menu className="cursor-pointer text-gray-700" size={30} />
          </button>
        </SheetTrigger>
        <SheetContent side="left" className="border-none bg-white">
          <div className="flex items-center gap-1 px-4">
            <div className="flex size-8 items-center justify-center rounded-lg bg-bank-gradient">
              <span className="text-sm font-bold text-white">CB</span>
            </div>
            <h1 className="text-26 font-bold font-ibm-plex-serif text-black-1">
              CoreBank
            </h1>
          </div>
          <div className="mobilenav-sheet">
            <SheetClose asChild>
              <nav className="flex h-full flex-col gap-6 pt-16 text-white">
                {navLinks.map((item) => {
                  const isActive =
                    pathname === item.href ||
                    pathname.startsWith(`${item.href}/`)
                  const Icon = item.icon

                  return (
                    <SheetClose asChild key={item.href}>
                      <Link
                        href={item.href}
                        className={cn(
                          "mobilenav-sheet_close w-full",
                          isActive && "bg-bank-gradient"
                        )}
                      >
                        <Icon
                          className={cn(
                            "size-5 text-gray-600",
                            isActive && "brightness-0 invert text-white"
                          )}
                        />
                        <p
                          className={cn(
                            "text-16 font-semibold text-black-2",
                            isActive && "text-white"
                          )}
                        >
                          {item.label}
                        </p>
                      </Link>
                    </SheetClose>
                  )
                })}
              </nav>
            </SheetClose>

            <div className="flex cursor-pointer items-center gap-3 py-6">
              <div className="footer_name-mobile">
                <p className="text-xl font-bold text-gray-700">DU</p>
              </div>
              <div className="footer_email-mobile">
                <p className="text-14 font-semibold text-gray-700">Demo User</p>
                <p className="text-14 font-normal text-gray-600">demo_user</p>
              </div>
            </div>
          </div>
        </SheetContent>
      </Sheet>
    </section>
  )
}
