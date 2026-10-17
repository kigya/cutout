import { act, render, screen, userEvent } from '@testing-library/react-native';
import { AccessibilityInfo, DeviceEventEmitter, Linking } from 'react-native';
import App from './App';

declare const global: { __erase: Record<string, jest.Mock> };
const native = global.__erase;
async function flush() { await act(async () => { for (let i = 0; i < 10; i += 1) await Promise.resolve(); }); }
async function boot() { await render(<App />); await flush(); await act(async () => { jest.advanceTimersByTime(1500); }); await flush(); }
beforeEach(() => {
  jest.useFakeTimers(); jest.clearAllMocks();
  AccessibilityInfo.isReduceMotionEnabled = jest.fn(async () => false);
  native.getPreferences = jest.fn(async () => ({ theme: 'system', analytics: true, crashes: true, diagnosticsNoticePending: true }));
  native.acknowledgeDiagnosticsNotice = jest.fn(async () => undefined);
  native.setPreferences = jest.fn(async () => undefined);
  native.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  native.getHome.mockResolvedValue({ rows: [], bubbleEnabled: false, clipboardReady: false, showXiaomiTip: false, bubbleSupported: false });
  native.consumePendingEdit.mockResolvedValue(null);
});
afterEach(() => { jest.useRealTimers(); jest.restoreAllMocks(); });

test('Home opens on time while the single model prewarm is still pending and handles its failure', async () => {
  let fail: (error: Error) => void = () => undefined;
  native.prepareModel.mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject; }));
  await boot();
  expect(native.prepareModel).toHaveBeenCalledTimes(1);
  await act(async () => { DeviceEventEmitter.emit('bubbleEnabled', { enabled: false }); });
  await act(async () => { DeviceEventEmitter.emit('bubbleEnabled', { enabled: false }); });
  expect(screen.getByText('Cutout')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Cut here' })).toBeEnabled();
  await act(async () => { fail(new Error('model not ready')); }); await flush();
  expect(screen.getByRole('button', { name: 'Cut here' })).toBeEnabled();
});

test('diagnostics notice links to policy and Settings and persists dismissal only after native acknowledgement', async () => {
  const open = jest.spyOn(Linking, 'openURL').mockRejectedValueOnce(new Error('browser unavailable')).mockResolvedValue(undefined);
  await boot();
  const user = userEvent.setup();
  await user.press(screen.getByRole('button', { name: 'Privacy policy' })); await flush();
  expect(open).toHaveBeenCalledWith('https://kigya.github.io/cutout/privacy/');
  expect(screen.getByText('Could not open the privacy policy')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Privacy policy' })); await flush();
  native.acknowledgeDiagnosticsNotice.mockRejectedValueOnce(new Error('write failed'));
  await user.press(screen.getByRole('button', { name: 'Dismiss' })); await flush();
  expect(screen.getByText('Diagnostics are enabled by default')).toBeTruthy();
  expect(screen.getByText('Could not save')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Dismiss' })); await flush();
  expect(screen.queryByText('Diagnostics are enabled by default')).toBeNull();
  await user.press(screen.getByRole('button', { name: 'Settings' })); await flush();
  expect(screen.getByRole('switch', { name: 'Share usage data' })).toBeChecked();
  expect(screen.getByRole('switch', { name: 'Share crash reports' })).toBeChecked();
  await user.press(screen.getByRole('switch', { name: 'Share usage data' })); await flush();
  expect(native.setPreferences).toHaveBeenLastCalledWith('system', false, true);
});


test('preference patches wait for saved consent and serialize rapid independent changes', async () => {
  let load!: (value: unknown) => void;
  let finishWrite!: () => void;
  native.getPreferences.mockImplementationOnce(() => new Promise(resolve => { load = resolve; }));
  native.setPreferences.mockImplementationOnce(() => new Promise<void>(resolve => { finishWrite = resolve; }));
  await boot();
  await userEvent.setup().press(screen.getByRole('button', { name: 'Settings' }));
  await userEvent.setup().press(screen.getByRole('switch', { name: 'Share usage data' }));
  await userEvent.setup().press(screen.getByRole('switch', { name: 'Share crash reports' }));
  await userEvent.setup().press(screen.getByRole('button', { name: 'Light' }));
  await flush();
  expect(native.setPreferences).not.toHaveBeenCalled();
  await act(async () => { load({ theme: 'system', analytics: false, crashes: true }); }); await flush();
  expect(native.setPreferences.mock.calls).toEqual([['system', false, true]]);
  await act(async () => { finishWrite(); }); await flush();
  expect(native.setPreferences.mock.calls).toEqual([
    ['system', false, true], ['system', false, false], ['light', false, false],
  ]);
  expect(screen.getByRole('switch', { name: 'Share usage data' })).not.toBeChecked();
  expect(screen.getByRole('switch', { name: 'Share crash reports' })).not.toBeChecked();
});

test('failed saved-preference reads and writes never persist enabled fallback over opt-outs', async () => {
  native.getPreferences.mockRejectedValue(new Error('preferences unavailable'));
  await boot();
  const user = userEvent.setup();
  await user.press(screen.getByRole('button', { name: 'Settings' }));
  await user.press(screen.getByRole('button', { name: 'Light' })); await flush();
  expect(native.setPreferences).not.toHaveBeenCalled();
  expect(screen.getByText('Could not save')).toBeTruthy();
  native.getPreferences.mockResolvedValue({ theme: 'system', analytics: false, crashes: false });
  native.setPreferences.mockRejectedValueOnce(new Error('write unavailable'));
  await user.press(screen.getByRole('button', { name: 'Dark' })); await flush();
  expect(native.setPreferences).toHaveBeenLastCalledWith('dark', false, false);
  await user.press(screen.getByRole('button', { name: 'Light' })); await flush();
  expect(native.setPreferences).toHaveBeenLastCalledWith('light', false, false);
  expect(screen.getByRole('switch', { name: 'Share usage data' })).not.toBeChecked();
});
