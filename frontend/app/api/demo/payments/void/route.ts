import { NextRequest, NextResponse } from "next/server";
import {
  postVoidHold,
  type DemoVoidRequest,
  type DemoVoidResponse,
} from "@/lib/api";

export async function POST(request: NextRequest) {
  try {
    const body: DemoVoidRequest = await request.json();
    const data: DemoVoidResponse = await postVoidHold(body);
    return NextResponse.json(data);
  } catch (err) {
    const message = err instanceof Error ? err.message : "Failed to void hold";
    const status =
      message.includes("not found") ? 404
      : message.includes("not allowed") ? 409
      : message.includes("demo account") ? 400
      : 502;
    return NextResponse.json({ message }, { status });
  }
}
