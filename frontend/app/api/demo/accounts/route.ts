import { NextResponse } from "next/server";
import { getDemoAccounts } from "@/lib/api";

export async function GET() {
  try {
    const accounts = await getDemoAccounts();
    return NextResponse.json({ accounts });
  } catch (err) {
    return NextResponse.json(
      { message: err instanceof Error ? err.message : "Unknown error" },
      { status: 500 }
    );
  }
}
