import { NextRequest, NextResponse } from "next/server";
import {
  getAccountHolds,
  type DemoHoldPage,
} from "@/lib/api";

export async function GET(
  request: NextRequest,
  { params }: { params: { accountId: string } }
) {
  const { searchParams } = new URL(request.url);
  const page = parseInt(searchParams.get("page") ?? "0", 10);
  const size = parseInt(searchParams.get("size") ?? "20", 10);

  try {
    const data: DemoHoldPage = await getAccountHolds(
      params.accountId,
      page,
      size
    );
    return NextResponse.json(data);
  } catch (err) {
    const message = err instanceof Error ? err.message : "Failed to fetch holds";
    return NextResponse.json({ message }, { status: 502 });
  }
}
