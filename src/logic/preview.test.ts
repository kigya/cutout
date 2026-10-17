import { clearHeldStroke, previewFrame } from './preview';

describe('preview', () => {
  test('a wide picture stays inside the window', () => {
    const frame = previewFrame(400, 800, 200, 100);
    expect(frame.width).toBeLessThanOrEqual(360);
    expect(frame.height).toBeGreaterThan(0);
  });

  test('a tall picture is limited by height', () => {
    const frame = previewFrame(800, 400, 100, 800);
    expect(frame.height).toBeLessThanOrEqual(200);
    expect(frame.width).toBeGreaterThanOrEqual(1);
  });

  test('the stroke stays until the new picture is decoded', () => {
    expect(clearHeldStroke(true, false)).toBe(false);
    expect(clearHeldStroke(false, true)).toBe(false);
    expect(clearHeldStroke(true, true)).toBe(true);
  });
});
