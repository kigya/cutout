export type BubbleAction = 'hide' | 'set';

export const BUBBLE_ALPHA = 0.72;

export function bubbleEnabledAfter(action: BubbleAction, requested = false): boolean {
  if (action === 'hide') return false;
  return requested;
}

export const BUBBLE_CHIP = 76;
export const BUBBLE_EDGE = 8;
export const BUBBLE_PANEL = 260;

export function bubbleFrame(input: {
  expanded: boolean;
  phase: string;
  editReady: boolean;
}): { width: number; height: number } {
  const chip = BUBBLE_CHIP + BUBBLE_EDGE;
  if (!input.expanded) return { width: chip, height: chip };
  const busy = input.phase === 'working';
  const result = input.phase === 'copied' || (input.phase === 'failed' && input.editReady);
  const height = busy ? 340 : result ? 400 : 260;
  return { width: chip + 12 + BUBBLE_PANEL, height };
}

export function bubbleBackdrop(alpha = BUBBLE_ALPHA): string {
  const value = Math.min(1, Math.max(0, alpha));
  return `rgba(18, 19, 24, ${value})`;
}
