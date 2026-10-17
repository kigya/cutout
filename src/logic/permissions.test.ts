import { blocksInAppCutout, bubbleCanStart, permissionKinds, permissionNeed } from './permissions';

describe('permissions', () => {
  test('cutting inside the app does not require a permission', () => {
    for (const kind of permissionKinds()) {
      expect(blocksInAppCutout(kind)).toBe(false);
      expect(permissionNeed(kind)).toBe(kind === 'overlay' ? 'requiredForBubble' : 'optional');
    }
  });

  test('the bubble starts only when it is enabled and the overlay is granted', () => {
    expect(bubbleCanStart(true, true)).toBe(true);
    expect(bubbleCanStart(true, false)).toBe(false);
    expect(bubbleCanStart(false, true)).toBe(false);
    expect(bubbleCanStart(false, false)).toBe(false);
  });
});
