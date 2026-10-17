import { mergeInsets } from './systemBars';

describe('system bars', () => {
  test('the larger inset wins, and a missing window starts at zero', () => {
    expect(mergeInsets({ top: 10, right: 1, bottom: 0, left: 2 }, { top: 4, right: 0, bottom: 8, left: 0 })).toEqual({
      top: 10,
      right: 1,
      bottom: 8,
      left: 2,
    });
    expect(mergeInsets({ top: 0, right: 0, bottom: 0, left: 0 }, null)).toEqual({
      top: 0,
      right: 0,
      bottom: 0,
      left: 0,
    });
  });
});
