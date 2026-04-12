"use client"

import { useRouter, useSearchParams } from "next/navigation"

interface PaginationProps {
  page: number
  totalPages: number
}

export default function Pagination({ page, totalPages }: PaginationProps) {
  const router = useRouter()
  const searchParams = useSearchParams()

  function navigate(targetPage: number) {
    const params = new URLSearchParams(searchParams.toString())
    params.set("page", String(targetPage))
    router.push(`?${params.toString()}`, { scroll: false })
  }

  return (
    <div className="flex items-center justify-between px-1 py-3">
      <button
        className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 disabled:cursor-not-allowed disabled:opacity-40"
        onClick={() => navigate(page - 1)}
        disabled={page <= 1}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M15 18l-6-6 6-6" />
        </svg>
        Previous
      </button>

      <span className="text-sm text-gray-500">
        Page {page} of {totalPages}
      </span>

      <button
        className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 disabled:cursor-not-allowed disabled:opacity-40"
        onClick={() => navigate(page + 1)}
        disabled={page >= totalPages}
      >
        Next
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M9 18l6-6-6-6" />
        </svg>
      </button>
    </div>
  )
}
