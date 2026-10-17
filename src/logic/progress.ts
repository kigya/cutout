export const CUT_STAGES = [8, 18, 28, 32, 92, 100] as const;

export function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, Math.round(value)));
}
