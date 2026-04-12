import { NextResponse } from "next/server";
import { postInternalTransfer, DemoTransferRequest } from "@/lib/api";

export async function POST(request: Request) {
  try {
    const body: DemoTransferRequest = await request.json();
    const result = await postInternalTransfer(body);
    return NextResponse.json(result);
  } catch (err) {
    return NextResponse.json(
      { message: err instanceof Error ? err.message : "Unknown error" },
      { status: 500 }
    );
  }
}
