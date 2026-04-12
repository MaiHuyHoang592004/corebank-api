import { NextResponse } from "next/server";
import { getAccountActivity } from "@/lib/api";

export async function GET(
  request: Request,
  { params }: { params: { accountId: string } }
) {
  try {
    const { searchParams } = new URL(request.url);
    const page = parseInt(searchParams.get("page") ?? "0", 10);
    const size = parseInt(searchParams.get("size") ?? "20", 10);
    const data = await getAccountActivity(params.accountId, page, size);
    return NextResponse.json(data);
  } catch (err) {
    return NextResponse.json(
      { message: err instanceof Error ? err.message : "Unknown error" },
      { status: 500 }
    );
  }
}
