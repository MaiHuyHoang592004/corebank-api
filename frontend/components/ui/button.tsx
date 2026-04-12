import * as React from "react"

import { cn } from "@/lib/utils"

const buttonVariants = {
  default: "bg-bank-gradient text-white hover:opacity-90",
  outline: "border border-gray-300 bg-white text-gray-700 hover:bg-gray-50",
}

const buttonSizes = {
  default: "h-10 px-4 py-2",
  sm: "h-8 px-3 text-xs",
}

const Button = React.forwardRef<
  HTMLButtonElement,
  React.ButtonHTMLAttributes<HTMLButtonElement> & {
    variant?: keyof typeof buttonVariants
    size?: keyof typeof buttonSizes
  }
>(({ className, variant = "default", size = "default", ...props }, ref) => {
  return (
    <button
      className={cn(
        "inline-flex items-center justify-center whitespace-nowrap rounded-md text-sm font-medium ring-offset-background transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50",
        buttonVariants[variant],
        buttonSizes[size],
        className
      )}
      ref={ref}
      {...props}
    />
  )
})
Button.displayName = "Button"

export { Button }
