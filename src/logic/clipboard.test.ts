import { canUseClipboard } from './clipboard';

describe('clipboard', () => {
  test('the clipboard action is pressable only when the clip is a picture', () => {
    expect(canUseClipboard(true)).toBe(true);
    expect(canUseClipboard(false)).toBe(false);
  });
});
