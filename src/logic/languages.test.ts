import { LANGUAGES, resolveLocale, sortLanguages } from './languages';

describe('languages', () => {
  test('lists thirty languages in native-name order', () => {
    const sorted = sortLanguages();
    expect(sorted).toHaveLength(30);
    expect(new Set(sorted.map(language => language.code)).size).toBe(30);
    const names = sorted.map(language => language.nativeName);
    const ordered = [...names].sort((left, right) => left.localeCompare(right, 'en', { sensitivity: 'base' }));
    expect(names).toEqual(ordered);
    expect(LANGUAGES.every(language => language.flag.length > 0)).toBe(true);
  });

  test('follows a supported system language and falls back to English', () => {
    expect(resolveLocale('ru-RU')).toBe('ru');
    expect(resolveLocale('zh-CN')).toBe('zh-Hans');
    expect(resolveLocale('zh-TW')).toBe('zh-Hant');
    expect(resolveLocale('pt-BR')).toBe('pt');
    expect(resolveLocale('fil-PH')).toBe('fil');
    expect(resolveLocale('iw')).toBe('he');
    expect(resolveLocale('zh')).toBe('zh-Hans');
    expect(resolveLocale('in')).toBe('id');
    expect(resolveLocale('tl')).toBe('fil');
    expect(resolveLocale('sv-SE')).toBe('sv');
    expect(resolveLocale('xx')).toBe('en');
  });
});
