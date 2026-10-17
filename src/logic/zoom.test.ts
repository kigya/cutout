import { clampPan, clampScale, panForZoom } from './zoom';

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
});
