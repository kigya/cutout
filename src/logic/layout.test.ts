import { contentMax, pagePadding, windowTier } from './layout';

test('window size picks the tier, the column, and the page inset', () => {
  expect(windowTier(839)).toBe('medium');
  expect(windowTier(840)).toBe('expanded');
  expect(windowTier(599)).toBe('compact');
  expect(contentMax(900)).toBe(640);
  expect(contentMax(700)).toBe(560);
  expect(contentMax(400)).toBeUndefined();
  expect(pagePadding(359)).toBe(16);
  expect(pagePadding(360)).toBe(20);
});
