export const CUT_STAGES = [8, 18, 28, 32, 92, 100] as const;

export function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, Math.round(value)));
}

/** Presentation estimate between native milestones; never completes an operation. */
export function estimatePercent(base: number, reported: number, elapsedMs: number): number {
  const cap = reported < 28 ? 27 : reported < 92 ? 90 : 98;
  const pace = reported < 28 ? 9000 : reported < 92 ? 16000 : 2500;
  const elapsed = Math.max(0, elapsedMs);
  return Math.max(base, Math.floor(base + (cap - base) * elapsed / (elapsed + pace)));
}
