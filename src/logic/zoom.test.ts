import { clampPan, clampScale, panForZoom, positiveScaleOrOne } from './zoom';

describe('zoom', () => {
  test('scale stays between one and six', () => {
    expect(clampScale(0.2)).toBe(1);
    expect(clampScale(2)).toBe(2);
    expect(clampScale(9)).toBe(6);
  });

  test('pan is pinned so the picture cannot leave the frame', () => {
    expect(clampPan(40, 100, 1)).toBe(0);
    expect(clampPan(40, 0, 2)).toBe(0);
    expect(clampPan(80, 100, 2)).toBe(50);
    expect(clampPan(-80, 100, 2)).toBe(-50);
    expect(clampPan(10, 100, 2)).toBe(10);
  });

  test('zooming keeps the focal point still', () => {
    const next = panForZoom(80, 50, 0, 1, 2);
    expect(next).toBeCloseTo(80 - 50 - (80 - 50) * 2);
    expect(panForZoom(80, 50, 0, 0, 2)).toBeCloseTo(panForZoom(80, 50, 0, 1, 2));
  });

  test('uses a valid positive drawing scale when gestures report an invalid scale', () => {
    expect(positiveScaleOrOne(2.5)).toBe(2.5);
    expect(positiveScaleOrOne(0)).toBe(1);
    expect(positiveScaleOrOne(-3)).toBe(1);
  });
});
