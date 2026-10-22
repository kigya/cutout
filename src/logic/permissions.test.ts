import { blocksInAppCutout, bubbleCanStart, permissionKinds, permissionNeed, permissionNeedsHelp } from './permissions';

describe('permissions', () => {
  test('cutting inside the app does not require a permission', () => {
    for (const kind of permissionKinds()) {
      expect(blocksInAppCutout(kind)).toBe(false);
      expect(permissionNeed(kind)).toBe('optional');
      expect(permissionNeed(kind, true)).toBe(kind === 'overlay' ? 'requiredForBubble' : 'optional');
    }
    expect(permissionNeed('overlay', false)).toBe('optional');
    expect(permissionNeed('overlay', true)).toBe('requiredForBubble');
  });

  test('the bubble starts only when it is enabled and the overlay is granted', () => {
    expect(bubbleCanStart(true, true)).toBe(true);
    expect(bubbleCanStart(true, false)).toBe(false);
    expect(bubbleCanStart(false, true)).toBe(false);
    expect(bubbleCanStart(false, false)).toBe(false);
  });

  test('help sheet appears only after repeated denial or a permanent block', () => {
    expect(permissionNeedsHelp({})).toBe(false);
    expect(permissionNeedsHelp({ deniedCount: 1 })).toBe(false);
    expect(permissionNeedsHelp({ deniedCount: 2 })).toBe(true);
    expect(permissionNeedsHelp({ permanentlyDenied: true })).toBe(true);
    expect(permissionNeedsHelp({ deniedCount: 0, permanentlyDenied: false })).toBe(false);
  });
});
