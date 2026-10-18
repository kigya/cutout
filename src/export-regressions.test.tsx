import { act, render, screen, userEvent } from '@testing-library/react-native';
import { AccessibilityInfo, DeviceEventEmitter, Platform, ToastAndroid } from 'react-native';
import { BubbleApp } from './bubble/BubbleApp';
import { text } from './i18n/catalogs';
import { LocaleGate } from './i18n/LocaleGate';
import { Erase, type BubbleSnapshot } from './native/Erase';
import { ResultScreen } from './screens/ResultScreen';
import { EditorScreen } from './screens/EditorScreen';
import { SplashScreen, SPLASH_BACKGROUND } from './components/SplashScreen';

declare const global: { __erase: Record<string, jest.Mock>; __imageSize: { set(w: number, h: number): void } };
const native = global.__erase;
const result: BubbleSnapshot = { revision: 1, expanded: true, anchorEnd: true, phase: 'copied', previewPath: '/cut.png', editReady: true, readingClipboard: false, clipboardReady: true, message: null, percent: 100, rasterizedSvg: false };
const session = { originalPath: '/original.png', cutoutPath: '/cut.png', width: 2, height: 2 };
async function settle() { await act(async () => { for (let i = 0; i < 12; i += 1) await Promise.resolve(); }); }
async function frame(value: BubbleSnapshot) { await act(async () => { DeviceEventEmitter.emit('bubbleState', value); }); }

beforeEach(() => {
  jest.clearAllMocks();
  Platform.OS = 'android';
  AccessibilityInfo.isReduceMotionEnabled = jest.fn(async () => false);
  native.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  native.getBubbleState.mockResolvedValue(result);
  native.bubbleAction.mockReset().mockImplementation(async (action: string) => ({ ...result, revision: 10, expanded: action !== 'collapse' && action !== 'refine' }));
  native.publishPng.mockReset().mockResolvedValue('copied');
  native.saveToGallery.mockReset().mockResolvedValue('saved');
  native.readAlpha.mockResolvedValue('/////w==');
  native.writeMasked.mockResolvedValue({ status: 'ready', path: '/edited.png' });
});
afterEach(() => { jest.useRealTimers(); jest.restoreAllMocks(); });

test('Bubble successful exports collapse explicitly, preserve the result and leave just the translucent shortcut', async () => {
  const toast = jest.spyOn(ToastAndroid, 'show').mockImplementation(() => undefined);
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await settle();
  let finish: (value: string) => void = () => undefined;
  native.publishPng.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const imageSource = screen.getByLabelText('Done').props.source;
  const sizes = native.setBubbleSize.mock.calls.length;
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  expect(screen.getByRole('button', { name: 'Copy' })).toBeBusy();
  expect(screen.getByRole('button', { name: 'Save' })).not.toBeBusy();
  expect(screen.queryByText('Saving…')).toBeNull();
  expect(screen.getByLabelText('Done').props.source).toBe(imageSource);
  expect(native.setBubbleSize).toHaveBeenCalledTimes(sizes);
  await act(async () => { finish('copied'); }); await settle();
  expect(native.bubbleAction).toHaveBeenLastCalledWith('collapse');
  expect(toast).toHaveBeenLastCalledWith('Copied', ToastAndroid.SHORT);
  expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Hide bubble' })).toBeNull();
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ opacity: 0.7 });
  await frame({ ...result, revision: 11 });
  expect(screen.getByRole('button', { name: 'Save' })).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'Save' })); await settle();
  expect(toast).toHaveBeenLastCalledWith('Saved to gallery', ToastAndroid.SHORT);
  expect(native.hideBubble).not.toHaveBeenCalled();
});

test('Bubble keeps failed exports open and only acknowledges a successful editor launch', async () => {
  const toast = jest.spyOn(ToastAndroid, 'show').mockImplementation(() => undefined);
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await settle();
  native.saveToGallery.mockResolvedValueOnce('denied');
  await userEvent.press(screen.getByRole('button', { name: 'Save' })); await settle();
  expect(native.bubbleAction).not.toHaveBeenCalled();
  expect(screen.getByText('Could not save')).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'Edit in app' })); await settle();
  expect(native.bubbleAction).toHaveBeenLastCalledWith('refine');
  expect(toast).toHaveBeenLastCalledWith('Edit in app', ToastAndroid.SHORT);
});

test('Result labels and image source remain stable throughout Copy and saves are serialized', async () => {
  await render(<LocaleGate><ResultScreen session={session} onBack={() => undefined} onEdit={() => undefined} /></LocaleGate>); await settle();
  let finish: (value: string) => void = () => undefined;
  native.publishPng.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const source = screen.getByLabelText('Done').props.source;
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  expect(screen.getByRole('button', { name: 'Copy' })).toBeBusy();
  expect(screen.getByRole('button', { name: 'Save' })).not.toBeBusy();
  expect(screen.getByLabelText('Done').props.source).toBe(source);
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  expect(native.saveToGallery).not.toHaveBeenCalled();
  await act(async () => { finish('copied'); }); await settle();
  expect(screen.getByText('Copied')).toBeTruthy();
});

test('iOS save denial explains Photos access and opens Settings without blocking copy', async () => {
  Platform.OS = 'ios';
  await render(<LocaleGate><ResultScreen session={session} onBack={() => undefined} onEdit={() => undefined} /></LocaleGate>); await settle();
  native.saveToGallery.mockResolvedValueOnce('denied');
  await userEvent.press(screen.getByRole('button', { name: 'Save' })); await settle();
  expect(screen.getByText(text('en', 'photosPermissionTitle'))).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: text('en', 'openSettings') })); await settle();
  expect(Erase.openPermission).toHaveBeenCalledWith('photos');
});

test('Editor serializes Copy and Save for the current mask', async () => {
  global.__imageSize.set(2, 2);
  await render(<LocaleGate><EditorScreen {...session} onBack={() => undefined} /></LocaleGate>); await settle();
  let finish: (value: string) => void = () => undefined;
  native.publishPng.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const copy = screen.getByRole('button', { name: 'Copy' });
  await act(async () => { copy.props.onClick(); copy.props.onClick(); }); await settle();
  expect(screen.getByRole('button', { name: 'Copy' })).toBeBusy();
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  expect(native.saveToGallery).not.toHaveBeenCalled();
  expect(native.writeMasked).toHaveBeenCalledTimes(1);
  await act(async () => { finish('copied'); }); await settle();
  expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();
});

test('Splash uses the native blue and completes at 1500ms independently of initialization', async () => {
  jest.useFakeTimers();
  const done = jest.fn();
  await render(<SplashScreen onDone={done} />);
  expect(screen.getByTestId('splash')).toHaveStyle({ backgroundColor: SPLASH_BACKGROUND });
  await act(async () => { jest.advanceTimersByTime(1499); });
  expect(done).not.toHaveBeenCalled();
  await act(async () => { jest.advanceTimersByTime(1); });
  expect(done).toHaveBeenCalledTimes(1);
});

test('iOS Photos rationale can be dismissed or handle a settings launch error in the editor', async () => {
  Platform.OS = 'ios'; global.__imageSize.set(2, 2);
  await render(<LocaleGate><EditorScreen {...session} onBack={() => undefined} /></LocaleGate>); await settle();
  native.saveToGallery.mockResolvedValue('denied');
  await userEvent.press(screen.getByRole('button', { name: 'Save' })); await settle();
  await userEvent.press(screen.getByRole('button', { name: text('en', 'notNow') })); await settle();
  expect(screen.queryByText(text('en', 'photosPermissionTitle'))).toBeNull();
  await userEvent.press(screen.getByRole('button', { name: 'Save' })); await settle();
  native.openPermission.mockRejectedValueOnce(new Error('settings unavailable'));
  await userEvent.press(screen.getByRole('button', { name: text('en', 'openSettings') })); await settle();
  expect(screen.getByText('Could not save')).toBeTruthy();
});

test('Result Photos rationale can close without granting access and reports a Settings launch failure', async () => {
  Platform.OS = 'ios';
  await render(<LocaleGate><ResultScreen session={session} onBack={() => undefined} onEdit={() => undefined} /></LocaleGate>); await settle();
  native.saveToGallery.mockResolvedValue('denied');
  await userEvent.press(screen.getByRole('button', { name: 'Save' })); await settle();
  await userEvent.press(screen.getByRole('button', { name: text('en', 'notNow') })); await settle();
  await userEvent.press(screen.getByRole('button', { name: 'Save' })); await settle();
  native.openPermission.mockRejectedValueOnce(new Error('settings unavailable'));
  await userEvent.press(screen.getByRole('button', { name: text('en', 'openSettings') })); await settle();
  expect(screen.getByText('Could not save')).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'Copy' })); await settle();
  expect(native.publishPng).toHaveBeenCalled();
});
