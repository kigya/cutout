export function clampScale(scale: number): number {
  'worklet';
  if (scale < 1) return 1;
  if (scale > 6) return 6;
  return scale;
}

export function clampPan(pan: number, view: number, scale: number): number {
  'worklet';
  if (scale <= 1 || view <= 0) return 0;
  const limit = (view * (scale - 1)) / 2;
  if (pan < -limit) return -limit;
  if (pan > limit) return limit;
  return pan;
}

export function panForZoom(
  focal: number,
  origin: number,
  previousPan: number,
  previousScale: number,
  nextScale: number,
): number {
  'worklet';
  const safe = previousScale > 0 ? previousScale : 1;
  const local = (focal - origin - previousPan) / safe + origin;
  return focal - origin - (local - origin) * nextScale;
}
