import { Erase, type BubbleAppearance } from '../native/Erase';

export const DEFAULT_BUBBLE_APPEARANCE: BubbleAppearance = {
  sizeDp: 64, opacity: 0.7, shrinkWhenIdle: false, shrinkToDot: false,
};

export function normalizeBubbleAppearance(value: Partial<BubbleAppearance>): BubbleAppearance {
  return {
    sizeDp: Number.isFinite(value.sizeDp) ? Math.round((Math.max(48, Math.min(88, value.sizeDp!)) - 48) / 4) * 4 + 48 : 64,
    opacity: Number.isFinite(value.opacity) ? Math.round(Math.max(0.2, Math.min(1, value.opacity!)) * 20) / 20 : 0.7,
    shrinkWhenIdle: value.shrinkWhenIdle === true,
    shrinkToDot: value.shrinkToDot === true,
  };
}

// Shared across settings routes, including when a write outlives its screen.
let writes: Promise<unknown> = Promise.resolve();
export async function readBubbleAppearance(): Promise<BubbleAppearance> {
  await writes.catch(() => undefined);
  return normalizeBubbleAppearance(await Erase.getBubbleAppearance());
}
export function writeBubbleAppearance(value: BubbleAppearance): Promise<BubbleAppearance> {
  const next = normalizeBubbleAppearance(value);
  const write = writes.catch(() => undefined).then(() =>
    Erase.setBubbleAppearance(next.sizeDp, next.opacity, next.shrinkWhenIdle, next.shrinkToDot)
  );
  writes = write;
  return write;
}
