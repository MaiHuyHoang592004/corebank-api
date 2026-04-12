export function normalizeUiPage(pageParam: string | undefined): number {
  const parsed = Number.parseInt(pageParam ?? "", 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return 1;
  }
  return parsed;
}

export function toApiPage(uiPage: number): number {
  return Math.max(0, uiPage - 1);
}
