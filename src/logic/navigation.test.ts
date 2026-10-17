import { backTarget } from './navigation';

describe('back navigation', () => {
  test('settings, language, result and the editor stay inside the app', () => {
    expect(backTarget('settings')).toBe('home');
    expect(backTarget('language')).toBe('settings');
    expect(backTarget('processing')).toBe('home');
    expect(backTarget('result')).toBe('home');
    expect(backTarget('editor')).toBe('home');
    expect(backTarget('home')).toBeNull();
  });
});
