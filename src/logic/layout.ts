export type WindowTier = 'compact' | 'medium' | 'expanded';

export function windowTier(width: number): WindowTier {
  if (width >= 840) return 'expanded';
  if (width >= 600) return 'medium';
  return 'compact';
}

export function contentMax(width: number): number | undefined {
  const tier = windowTier(width);
  if (tier === 'expanded') return 640;
  if (tier === 'medium') return 560;
  return undefined;
}

export function pagePadding(width: number): number {
  return width < 360 ? 16 : 20;
}
