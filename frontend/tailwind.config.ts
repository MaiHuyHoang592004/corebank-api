import type { Config } from "tailwindcss";

const config: Config = {
  darkMode: ["class"],
  content: [
    "./pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        bankGradient: "var(--theme-primary-gradient)",
        "bank-gradient-start": "#6685CC",
        "bank-gradient-end": "#85A5FF",
        indigo: {
          DEFAULT: "#6685CC",
          25: "#F0F3FF",
          50: "#E0E7FF",
          100: "#C7D2FE",
          200: "#A5B4FC",
          300: "#818CF8",
          400: "#6685CC",
          500: "#6366F1",
          600: "#4F46E5",
          700: "#4338CA",
          800: "#3730A3",
          900: "#312E81",
          950: "#1E1B4B",
        },
        success: {
          DEFAULT: "#16A34A",
          light: "#22C55E",
          dark: "#15803D",
        },
        pink: {
          DEFAULT: "#EC4899",
          light: "#F472B6",
          dark: "#DB2777",
        },
        blue: {
          DEFAULT: "#2563EB",
          light: "#60A5FA",
          dark: "#1D4ED8",
          25: "#EFF6FF",
        },
        gray: {
          25: "#FCFCFD",
          50: "#F9FAFB",
          100: "#F3F4F6",
          200: "#E5E7EB",
          300: "#D1D5DB",
          400: "#9CA3AF",
          500: "#6B7280",
          600: "#4B5563",
          700: "#374151",
          800: "#1F2937",
          900: "#111827",
          950: "#030712",
        },
        "black-1": "#010101",
        "black-2": "#404040",
        "gray-6": "#F3F4F6",
        "primary-main": "#768BED",
        "primary-dark": "#5A72CC",
        "primary-light": "#C4CEF9",
        "primary-gradient": "linear-gradient(135deg, #768BED 0%, #A5B4FC 100%)",
        "bg-secondary": "#F3F4F6",
        "bg-bank-gradient": "linear-gradient(135deg, #6685CC 0%, #85A5FF 100%)",
        "theme-primary-gradient": "linear-gradient(135deg, #6685CC 0%, #85A5FF 100%)",
      },
      fontFamily: {
        inter: ["var(--font-inter)"],
        "ibm-plex-serif": ["var(--font-ibm-plex-serif)"],
      },
      boxShadow: {
        chart: "0px 15px 20px -15px rgba(157, 162, 184, 0.6)",
        profile: "0px 10px 40px rgba(0,0,0,0.1)",
        creditCard: "0px 2px 5px rgba(0,0,0,0.08)",
        form: "0px 2px 5px rgba(0,0,0,0.05)",
        sidebar: "0px 4px 4px rgba(0,0,0,0.05)",
      },
      backgroundImage: {
        "bank-gradient": "linear-gradient(135deg, #6685CC 0%, #85A5FF 100%)",
        "gradient-mesh": "url('/icons/gradient-mesh.png')",
      },
      borderRadius: {
        lg: "0.75rem",
        md: "0.5rem",
        sm: "0.375rem",
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" },
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
};

export default config;
