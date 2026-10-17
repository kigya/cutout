import { hasRequiredLicenses, LICENSES } from './licenses';

describe('licenses', () => {
  test('the settings list names the on-device stack', () => {
    expect(hasRequiredLicenses()).toBe(true);
    expect(hasRequiredLicenses(LICENSES.filter(entry => entry.name !== 'ISNet'))).toBe(false);
  });
});
