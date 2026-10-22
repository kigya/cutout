import { applyStroke, applyStrokeInPlace, canCopyEdited, canReplay, containRect, opaqueCount, previousStrokePoint, rememberStroke, sameAlpha, strokePoints, viewToBitmap } from './stroke';

describe('stroke', () => {
  test('uses the latest sample, or the current point when a gesture has no prior sample', () => {
    const fallback = { x: 9, y: 7 };
    expect(previousStrokePoint([], fallback)).toBe(fallback);
    const previous = { x: 3, y: 2 };
    expect(previousStrokePoint([{ x: 1, y: 1 }, previous], fallback)).toBe(previous);
  });

  test('stamps along a segment and keeps a zero-length move', () => {
    expect(strokePoints({ x: 0, y: 0 }, { x: 10, y: 0 }, 5)).toEqual([
      { x: 5, y: 0 },
      { x: 10, y: 0 },
    ]);
    expect(strokePoints({ x: 1, y: 1 }, { x: 1, y: 1 }, 4)).toEqual([{ x: 1, y: 1 }]);
    expect(strokePoints({ x: 0, y: 0 }, { x: 3, y: 0 }, 0)).toEqual([{ x: 3, y: 0 }]);
  });

  test('erase clears the center and restore paints it back', () => {
    const start = Uint8Array.from([255, 255, 255, 255]);
    const erased = applyStroke(start, 2, 2, [{ x: 0.5, y: 0.5 }], 1, 'erase');
    expect(erased[0]).toBe(0);
    expect(erased[3]).toBe(255);
    const restored = applyStroke(erased, 2, 2, [{ x: 0.5, y: 0.5 }], 1, 'restore');
    expect(restored[0]).toBe(255);
    expect(canCopyEdited(restored)).toBe(true);
  });

  test('incremental live compositing matches the committed full-sample stroke', () => {
    const initial = new Uint8Array(100).fill(173);
    const samples = [
      { x: 1.5, y: 1.5 },
      ...strokePoints({ x: 1.5, y: 1.5 }, { x: 5.5, y: 2.5 }, 1),
      ...strokePoints({ x: 5.5, y: 2.5 }, { x: 7.5, y: 6.5 }, 1),
    ];
    const committed = applyStroke(initial, 10, 10, samples, 2.5, 'erase');
    const live = new Uint8Array(initial);
    applyStrokeInPlace(live, 10, 10, samples.slice(0, 1), 2.5, 'erase');
    applyStrokeInPlace(live, 10, 10, samples.slice(1, 6), 2.5, 'erase');
    applyStrokeInPlace(live, 10, 10, samples.slice(6), 2.5, 'erase');
    expect(live).toEqual(committed);
    expect(initial).toEqual(new Uint8Array(100).fill(173));
  });

  test('ignores an empty stroke, a dead radius, and a zero-size frame', () => {
    const start = Uint8Array.from([10, 20]);
    expect(Array.from(applyStroke(start, 2, 1, [], 4, 'erase'))).toEqual([10, 20]);
    expect(Array.from(applyStroke(start, 2, 1, [{ x: 0, y: 0 }], 0, 'erase'))).toEqual([10, 20]);
    expect(Array.from(applyStroke(start, 0, 1, [{ x: 0, y: 0 }], 4, 'restore'))).toEqual([10, 20]);
    expect(Array.from(applyStroke(start, 2, 0, [{ x: 0, y: 0 }], 4, 'erase'))).toEqual([10, 20]);
    expect(opaqueCount(Uint8Array.from([0, 8]))).toBe(1);
    expect(canCopyEdited(Uint8Array.from([0, 8]))).toBe(true);
    expect(opaqueCount(Uint8Array.from([0, 8, 9]), 8)).toBe(1);
  });

  test('maps a centered view into bitmap pixels', () => {
    expect(viewToBitmap(50, 25, 100, 50, 100, 50)).toEqual({ x: 50, y: 25 });
    expect(viewToBitmap(10, 10, 0, 10, 10, 10)).toEqual({ x: 0, y: 0 });
    expect(viewToBitmap(10, 10, 10, 0, 10, 10)).toEqual({ x: 0, y: 0 });
    expect(viewToBitmap(10, 10, 10, 10, 0, 10)).toEqual({ x: 0, y: 0 });
    expect(viewToBitmap(10, 10, 10, 10, 10, 0)).toEqual({ x: 0, y: 0 });
  });

  test('fits the session pixel frame once and shares that rectangle with touch mapping', () => {
    expect(containRect(300, 200, 4, 2)).toEqual({ x: 0, y: 25, width: 300, height: 150 });
    expect(containRect(200, 300, 2, 4)).toEqual({ x: 25, y: 0, width: 150, height: 300 });
    expect(containRect(0, 300, 2, 4)).toEqual({ x: 0, y: 0, width: 0, height: 0 });
    expect(viewToBitmap(150, 25, 300, 200, 4, 2)).toEqual({ x: 2, y: 0 });
    expect(viewToBitmap(150, 100, 300, 200, 4, 2)).toEqual({ x: 2, y: 1 });
  });

  test('does not eat the frame from a stamp outside it, and leaves a clear pixel clear', () => {
    const start = Uint8Array.from([255, 255, 255, 255]);
    expect(Array.from(applyStroke(start, 2, 2, [{ x: -8, y: 0.5 }], 20, 'erase'))).toEqual([255, 255, 255, 255]);
    const punched = Uint8Array.from([0, 255]);
    const erased = applyStroke(punched, 2, 1, [{ x: 0.5, y: 0.5 }], 2, 'erase');
    expect(erased[0]).toBe(0);
    expect(erased[1]).toBeLessThan(255);
  });

  test('compares masks and keeps a short undo stack', () => {
    expect(sameAlpha(Uint8Array.from([1]), Uint8Array.from([1, 2]))).toBe(false);
    expect(sameAlpha(Uint8Array.from([1, 2]), Uint8Array.from([1, 3]))).toBe(false);
    expect(sameAlpha(Uint8Array.from([1, 2]), Uint8Array.from([1, 2]))).toBe(true);
    const past: Uint8Array[] = [];
    for (let index = 0; index < 12; index += 1) rememberStroke(past, Uint8Array.from([index]));
    expect(past).toHaveLength(12);
    rememberStroke(past, Uint8Array.from([99]));
    expect(past).toHaveLength(12);
    expect(past[0][0]).toBe(1);
    expect(canReplay(undefined, new Uint8Array())).toBe(false);
    expect(canReplay(new Uint8Array(), null)).toBe(false);
    expect(canReplay(new Uint8Array(), new Uint8Array())).toBe(true);
  });

  test('undoes pinch zoom around the center before mapping pixels', () => {
    expect(viewToBitmap(50, 25, 100, 50, 100, 50, { scale: 2, tx: 0, ty: 0 })).toEqual({ x: 50, y: 25 });
    expect(viewToBitmap(100, 25, 100, 50, 100, 50, { scale: 2, tx: 0, ty: 0 })).toEqual({ x: 75, y: 25 });
    expect(viewToBitmap(100, 25, 100, 50, 100, 50, { scale: 0, tx: 0, ty: 0 })).toEqual({ x: 100, y: 25 });
  });
});
