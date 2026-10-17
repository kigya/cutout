import { appendStroke, replayStrokes, type MaskStroke, type StrokeHistory } from './history';

const point = (x: number): MaskStroke => ({ points: [{ x, y: 1 }], radius: 1, tool: 'erase' });

describe('mask stroke history', () => {
  test('replays image-coordinate strokes for undo and redo', () => {
    const initial = new Uint8Array(16).fill(255);
    const strokes = [point(1), { ...point(3), tool: 'restore' as const }];
    const edited = replayStrokes(initial, 4, 4, strokes);
    expect(edited[5]).toBeLessThan(255);
    expect(edited[7]).toBe(255);
    expect(replayStrokes(initial, 4, 4, strokes.slice(0, 1))[5]).toBeLessThan(255);
    expect(replayStrokes(initial, 4, 4, strokes.slice(0, 0))).toEqual(initial);
  });

  test('keeps only the latest 50 image-coordinate actions', () => {
    let history: StrokeHistory = { base: new Uint8Array(16).fill(255), strokes: [], index: 0 };
    for (let x = 0; x < 55; x += 1) history = appendStroke(history, point(x), 4, 4);
    expect(history.strokes).toHaveLength(50);
    expect(history.strokes[0].points[0].x).toBe(5);
    expect(history.strokes[49].points[0].x).toBe(54);
  });

  test('folds an evicted stroke into the checkpoint so undo preserves older edits', () => {
    let history: StrokeHistory = { base: new Uint8Array(102).fill(255), strokes: [], index: 0 };
    for (let x = 0; x < 51; x += 1) {
      history = appendStroke(history, { points: [{ x: x * 2 + 0.5, y: 0.5 }], radius: 0.6, tool: 'erase' }, 102, 1);
    }
    const current = replayStrokes(history.base, 102, 1, history.strokes.slice(0, history.index));
    const undoneLatest = replayStrokes(history.base, 102, 1, history.strokes.slice(0, history.index - 1));
    expect(history.strokes).toHaveLength(50);
    expect(undoneLatest[0]).toBe(0);
    expect(undoneLatest[100]).toBe(255);
    expect(current[100]).toBe(0);
  });
});
