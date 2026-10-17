import { catalogText, catalogs, localizeMessage, text } from './catalogs';
import { en, type MessageKey } from './messages/en';
import { LOCALE_CODES } from '../logic/languages';

describe('catalogs', () => {
  test('every language has the english keys', () => {
    const keys = Object.keys(en) as MessageKey[];
    for (const code of LOCALE_CODES) {
      for (const key of keys) {
        expect(catalogs[code][key].length).toBeGreaterThan(0);
      }
    }
    expect(text('ru', 'save')).toBe(catalogs.ru.save);
  });

  test('an empty catalog string falls back, and unknown codes stay visible', () => {
    expect(catalogText('', 'Saved')).toBe('Saved');
    expect(catalogText(undefined, 'Saved')).toBe('Saved');
    expect(catalogText('Готово', 'Saved')).toBe('Готово');
    expect(localizeMessage('en', '')).toBe('');
    expect(localizeMessage('en', null)).toBe('');
    expect(localizeMessage('en', 'copied')).toBe(en.copied);
    expect(localizeMessage('en', 'not-a-code')).toBe('not-a-code');
  });
});
