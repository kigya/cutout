import { appendStroke, commitStrokeHistory, redoStrokeHistory, replayStrokes, undoStrokeHistory, type MaskStroke, type StrokeHistory } from './history';

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

  test('commits only when a history exists and mask rendering succeeds', () => {
    const stroke = point(1);
    const history: StrokeHistory = { base: new Uint8Array(16).fill(255), strokes: [], index: 0 };
    const applyMask = jest.fn(() => true);
    const commit = jest.fn();
    expect(commitStrokeHistory(null, stroke, 4, 4, applyMask, commit)).toBe(false);
    expect(applyMask).not.toHaveBeenCalled();
    expect(commitStrokeHistory(history, stroke, 4, 4, () => false, commit)).toBe(false);
    expect(commit).not.toHaveBeenCalled();
    expect(commitStrokeHistory(history, stroke, 4, 4, applyMask, commit)).toBe(true);
    expect(commit).toHaveBeenCalledWith({ ...history, strokes: [stroke], index: 1 });
  });

  test('undo and redo only replay an available history step', () => {
    const history: StrokeHistory = { base: new Uint8Array(16), strokes: [point(1), point(2)], index: 1 };
    const replay = jest.fn();
    expect(undoStrokeHistory(null, replay)).toBe(false);
    expect(undoStrokeHistory({ ...history, index: 0 }, replay)).toBe(false);
    expect(undoStrokeHistory(history, replay)).toBe(true);
    expect(redoStrokeHistory(null, replay)).toBe(false);
    expect(redoStrokeHistory({ ...history, index: 2 }, replay)).toBe(false);
    expect(redoStrokeHistory(history, replay)).toBe(true);
    expect(replay.mock.calls).toEqual([[0], [2]]);
  });
});
