import { describe, expect, it } from "vitest";
import { normalizeUiPage, toApiPage } from "./activity-pagination";

describe("activity pagination helpers", () => {
  it("normalizes missing/invalid page param to ui page 1", () => {
    expect(normalizeUiPage(undefined)).toBe(1);
    expect(normalizeUiPage("")).toBe(1);
    expect(normalizeUiPage("abc")).toBe(1);
    expect(normalizeUiPage("-2")).toBe(1);
    expect(normalizeUiPage("0")).toBe(1);
  });

  it("keeps valid ui page values", () => {
    expect(normalizeUiPage("1")).toBe(1);
    expect(normalizeUiPage("2")).toBe(2);
    expect(normalizeUiPage("15")).toBe(15);
  });

  it("maps ui page to backend zero-based page", () => {
    expect(toApiPage(1)).toBe(0);
    expect(toApiPage(2)).toBe(1);
    expect(toApiPage(5)).toBe(4);
    expect(toApiPage(0)).toBe(0);
  });
});
