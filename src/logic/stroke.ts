export type Point = { x: number; y: number };
export type BrushTool = 'erase' | 'restore';

export function strokePoints(from: Point, to: Point, spacing: number): Point[] {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const distance = Math.hypot(dx, dy);
  if (distance === 0 || spacing <= 0) return [to];
  const steps = Math.ceil(distance / spacing);
  const points: Point[] = [];
  for (let step = 1; step <= steps; step += 1) {
    const t = step / steps;
    points.push({ x: from.x + dx * t, y: from.y + dy * t });
  }
  return points;
}

export function applyStroke(
  alpha: Uint8Array,
  width: number,
  height: number,
  points: readonly Point[],
  radius: number,
  tool: BrushTool,
): Uint8Array {
  const next = new Uint8Array(alpha);
  applyStrokeInPlace(next, width, height, points, radius, tool);
  return next;
}

export function applyStrokeInPlace(
  alpha: Uint8Array,
  width: number,
  height: number,
  points: readonly Point[],
  radius: number,
  tool: BrushTool,
): void {
  if (radius <= 0 || points.length === 0 || width <= 0 || height <= 0) return;
  const radiusSquared = radius * radius;
  for (const point of points) {
    if (point.x < 0 || point.y < 0 || point.x >= width || point.y >= height) continue;
    const minX = Math.max(0, Math.floor(point.x - radius));
    const maxX = Math.min(width - 1, Math.ceil(point.x + radius));
    const minY = Math.max(0, Math.floor(point.y - radius));
    const maxY = Math.min(height - 1, Math.ceil(point.y + radius));
    for (let y = minY; y <= maxY; y += 1) {
      for (let x = minX; x <= maxX; x += 1) {
        const dx = x + 0.5 - point.x;
        const dy = y + 0.5 - point.y;
        const distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > radiusSquared) continue;
        const index = y * width + x;
        const current = alpha[index];
        if (tool === 'erase') {
          if (current === 0) continue;
          const falloff = 1 - Math.sqrt(distanceSquared) / radius;
          alpha[index] = Math.round(current * (1 - falloff));
        } else {
          const falloff = 1 - Math.sqrt(distanceSquared) / radius;
          alpha[index] = Math.round(current + (255 - current) * falloff);
        }
      }
    }
  }
}

export function opaqueCount(alpha: Uint8Array, threshold = 0): number {
  let count = 0;
  for (let index = 0; index < alpha.length; index += 1) {
    if (alpha[index] > threshold) count += 1;
  }
  return count;
}

export function canCopyEdited(alpha: Uint8Array): boolean {
  return opaqueCount(alpha) > 0;
}

export function sameAlpha(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

export function rememberStroke(past: Uint8Array[], snapshot: Uint8Array, limit = 12): void {
  past.push(new Uint8Array(snapshot));
  if (past.length > limit) past.shift();
}

export function canReplay(step: Uint8Array | undefined, current: Uint8Array | null): boolean {
  return step != null && current != null;
}

export type ViewTransform = { scale: number; tx: number; ty: number };

export function viewToBitmap(
  x: number,
  y: number,
  viewWidth: number,
  viewHeight: number,
  imageWidth: number,
  imageHeight: number,
  transform: ViewTransform = { scale: 1, tx: 0, ty: 0 },
): Point {
  if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
    return { x: 0, y: 0 };
  }
  const userScale = transform.scale > 0 ? transform.scale : 1;
  const centerX = viewWidth / 2;
  const centerY = viewHeight / 2;
  const localX = (x - centerX - transform.tx) / userScale + centerX;
  const localY = (y - centerY - transform.ty) / userScale + centerY;
  const scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
  const left = (viewWidth - imageWidth * scale) / 2;
  const top = (viewHeight - imageHeight * scale) / 2;
  return { x: (localX - left) / scale, y: (localY - top) / scale };
}
