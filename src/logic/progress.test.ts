import { CUT_STAGES, clampPercent, estimatePercent } from './progress';

describe('cut progress', () => {
  test('moves through read, prepare, inference and save', () => {
    expect(CUT_STAGES).toEqual([8, 18, 28, 32, 92, 100]);
    expect(clampPercent(32.4)).toBe(32);
    expect(clampPercent(-4)).toBe(0);
    expect(clampPercent(140)).toBe(100);
    expect(clampPercent(Number.NaN)).toBe(0);
  });

  test('estimates time within each native stage without completing or moving backwards', () => {
    expect(estimatePercent(8, 8, -100)).toBe(8);
    expect(estimatePercent(8, 8, 9000)).toBe(17);
    expect(estimatePercent(28, 28, 16000)).toBe(59);
    expect(estimatePercent(92, 92, 2500)).toBe(95);
    expect(estimatePercent(70, 18, 9000)).toBe(70);
    expect(estimatePercent(28, 28, 600000)).toBeLessThanOrEqual(90);
    expect(estimatePercent(92, 100, 600000)).toBeLessThanOrEqual(98);
  });
});
