import { applyStroke, type BrushTool, type Point } from '../logic/stroke';

export type MaskStroke = {
  points: Point[];
  radius: number;
  tool: BrushTool;
};

export const MAX_STROKES = 50;

export type StrokeHistory = {
  base: Uint8Array;
  strokes: MaskStroke[];
  index: number;
};

export function appendStroke(history: StrokeHistory, stroke: MaskStroke, width: number, height: number): StrokeHistory {
  const strokes = history.strokes.slice(0, history.index);
  strokes.push(stroke);
  let base = history.base;
  if (strokes.length > MAX_STROKES) {
    base = replayStrokes(base, width, height, [strokes.shift()!]);
  }
  return { base, strokes, index: strokes.length };
}

export function replayStrokes(initial: Uint8Array, width: number, height: number, strokes: readonly MaskStroke[]): Uint8Array {
  let mask: Uint8Array = new Uint8Array(initial);
  for (const stroke of strokes) {
    mask = applyStroke(mask, width, height, stroke.points, stroke.radius, stroke.tool);
  }
  return mask;
}
