import { NextRequest, NextResponse } from "next/server";
import {
  postCaptureHold,
  type DemoCaptureRequest,
  type DemoCaptureResponse,
} from "@/lib/api";

export async function POST(request: NextRequest) {
  try {
    const body: DemoCaptureRequest = await request.json();
    const data: DemoCaptureResponse = await postCaptureHold(body);
    return NextResponse.json(data);
  } catch (err) {
    const message = err instanceof Error ? err.message : "Failed to capture hold";
    const status =
      message.includes("not found") ? 404
      : message.includes("not allowed") ? 409
      : message.includes("demo account") ? 400
      : 502;
    return NextResponse.json({ message }, { status });
  }
}
