import { NextResponse } from "next/server";
import { getDemoAccount } from "@/lib/api";

export async function GET(
  _request: Request,
  { params }: { params: { accountId: string } }
) {
  try {
    const account = await getDemoAccount(params.accountId);
    return NextResponse.json(account);
  } catch (err) {
    return NextResponse.json(
      { message: err instanceof Error ? err.message : "Unknown error" },
      { status: 500 }
    );
  }
}
