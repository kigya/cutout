import { Erase } from '../native/Erase';
import { DEFAULT_PREFERENCES, readPreferences, track, writePreferences } from './telemetry';

test('preferences default to enabled diagnostics and preserve explicit opt-out', async () => {
  delete Erase.getPreferences;
  expect(await readPreferences()).toEqual(DEFAULT_PREFERENCES);
  Erase.getPreferences = jest.fn().mockResolvedValue({ theme: 'dark', analytics: true, crashes: false });
  expect(await readPreferences()).toEqual({ theme: 'dark', analytics: true, crashes: false, diagnosticsNoticePending: false });
  Erase.getPreferences = jest.fn().mockResolvedValue({ theme: 'invalid', analytics: 1 });
  expect(await readPreferences()).toEqual({ ...DEFAULT_PREFERENCES, diagnosticsNoticePending: false });
  Erase.getPreferences = jest.fn().mockResolvedValue({ theme: 'light', analytics: false, crashes: false, diagnosticsNoticePending: true });
  expect(await readPreferences()).toEqual({ theme: 'light', analytics: false, crashes: false, diagnosticsNoticePending: true });
  Erase.getPreferences = jest.fn().mockRejectedValue(new Error('unavailable'));
  expect(await readPreferences()).toEqual(DEFAULT_PREFERENCES);
  await expect(readPreferences(true)).rejects.toThrow("unavailable");
});

test('native collection owns consent and receives only typed product events', async () => {
  delete Erase.setPreferences;
  await writePreferences(DEFAULT_PREFERENCES);
  Erase.setPreferences = jest.fn().mockResolvedValue(undefined);
  await writePreferences({ ...DEFAULT_PREFERENCES, analytics: true });
  expect(Erase.setPreferences).toHaveBeenCalledWith('system', true, true);
  delete Erase.logProductEvent;
  track('screen_view');
  Erase.logProductEvent = jest.fn().mockResolvedValue(undefined);
  track('cutout_completed', { source: 'gallery', surface: 'app', duration_ms: 1200 });
  expect(Erase.logProductEvent).toHaveBeenCalledWith('cutout_completed', { source: 'gallery', surface: 'app', duration_ms: 1200 });
  Erase.logProductEvent = jest.fn().mockRejectedValue(new Error('offline'));
  track('screen_view');
  await Promise.resolve();
});
