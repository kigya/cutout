import { CUT_STAGES, clampPercent } from './progress';

describe('cut progress', () => {
  test('moves through read, prepare, inference and save', () => {
    expect(CUT_STAGES).toEqual([8, 18, 28, 32, 92, 100]);
    expect(clampPercent(32.4)).toBe(32);
    expect(clampPercent(-4)).toBe(0);
    expect(clampPercent(140)).toBe(100);
    expect(clampPercent(Number.NaN)).toBe(0);
  });
});
