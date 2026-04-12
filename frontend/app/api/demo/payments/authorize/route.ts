import { NextRequest, NextResponse } from "next/server";
import {
  postAuthorizeHold,
  type DemoAuthorizeRequest,
  type DemoAuthorizeResponse,
} from "@/lib/api";

export async function POST(request: NextRequest) {
  try {
    const body: DemoAuthorizeRequest = await request.json();
    const data: DemoAuthorizeResponse = await postAuthorizeHold(body);
    return NextResponse.json(data);
  } catch (err) {
    const message = err instanceof Error ? err.message : "Failed to authorize hold";
    const status =
      message.includes("not found") ? 404
      : message.includes("not allowed") ? 409
      : message.includes("demo account") ? 400
      : 502;
    return NextResponse.json({ message }, { status });
  }
}
