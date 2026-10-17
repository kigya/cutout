import { bubbleBackdrop, bubbleEnabledAfter, bubbleFrame } from './bubble';

describe('bubble', () => {
  test('the cross hides the bubble until the app turns it on again', () => {
    expect(bubbleEnabledAfter('hide', true)).toBe(false);
    expect(bubbleEnabledAfter('hide')).toBe(false);
    expect(bubbleEnabledAfter('set', true)).toBe(true);
    expect(bubbleEnabledAfter('set', false)).toBe(false);
    expect(bubbleEnabledAfter('set')).toBe(false);
  });

  test('the panel grows with the phase and stays a chip when closed', () => {
    expect(bubbleFrame({ expanded: false, phase: 'idle', editReady: false })).toEqual({ width: 84, height: 84 });
    expect(bubbleFrame({ expanded: true, phase: 'idle', editReady: false }).height).toBe(260);
    expect(bubbleFrame({ expanded: true, phase: 'working', editReady: false }).height).toBe(340);
    expect(bubbleFrame({ expanded: true, phase: 'copied', editReady: true }).height).toBe(400);
    expect(bubbleFrame({ expanded: true, phase: 'failed', editReady: true }).height).toBe(400);
    expect(bubbleFrame({ expanded: true, phase: 'failed', editReady: false }).height).toBe(260);
  });

  test('the bubble backdrop stays inside the alpha range', () => {
    expect(bubbleBackdrop()).toBe('rgba(18, 19, 24, 0.72)');
    expect(bubbleBackdrop(-1)).toBe('rgba(18, 19, 24, 0)');
    expect(bubbleBackdrop(2)).toBe('rgba(18, 19, 24, 1)');
  });
});
