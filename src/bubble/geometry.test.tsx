import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AccessibilityInfo, DeviceEventEmitter, ToastAndroid } from 'react-native';
import { BubbleApp } from './BubbleApp';
import { LocaleGate } from '../i18n/LocaleGate';
import { Erase, type BubbleGeometry, type BubbleSnapshot } from '../native/Erase';

declare const global: { __erase: Record<string, jest.Mock> };
const native = global.__erase;
const state: BubbleSnapshot = { revision: 1, expanded: false, anchorEnd: true, phase: 'idle', clipboardReady: false, readingClipboard: false, message: null, previewPath: null, rasterizedSvg: false, editReady: false, percent: 0 };
const geometry: BubbleGeometry = { revision: 1, expanded: false, anchorEnd: true, windowWidthDp: 84, windowHeightDp: 84, surfaceWidthDp: 628, surfaceHeightDp: 400, chipSizeDp: 84, chipLeftDp: 272, chipTopDp: 0, panelWidthDp: 260, panelHeightDp: 260 };
async function flush() { await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); }); }
async function emit(name: string, value: unknown) { await act(async () => { DeviceEventEmitter.emit(name, value); }); await flush(); }
beforeEach(() => {
  jest.clearAllMocks();
  AccessibilityInfo.isReduceMotionEnabled = jest.fn(async () => false);
  native.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  native.getBubbleState.mockResolvedValue(state);
  native.getBubbleGeometry = jest.fn(async () => geometry);
  native.ackBubbleGeometry = jest.fn();
});

test('fixed surface and chip survive expand collapse and native orientation; JS never resizes the window', async () => {
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  const chip = screen.getByTestId('bubble-chip');
  expect(screen.getByTestId('bubble-surface')).toHaveStyle({ width: 628, height: 400 });
  expect(chip).toHaveStyle({ left: 272, top: 0, width: 84, height: 84 });
  expect(native.ackBubbleGeometry).toHaveBeenLastCalledWith(1);
  await emit('bubbleState', { ...state, revision: 2, expanded: true });
  await emit('bubbleGeometry', { ...geometry, revision: 2, expanded: true, windowWidthDp: 356, windowHeightDp: 260 });
  expect(screen.getByTestId('bubble-chip')).toBe(chip);
  expect(screen.getByTestId('bubble-panel')).toHaveStyle({ left: 0, height: 260 });
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ opacity: 0.7 });
  await emit('bubbleGeometry', { ...geometry, revision: 3, expanded: true, anchorEnd: false, chipLeftDp: 272 });
  expect(chip).toHaveStyle({ left: 272 });
  expect(screen.getByTestId('bubble-panel')).toHaveStyle({ left: 368 });
  await emit('bubbleGeometry', { ...geometry, revision: 2 });
  expect(native.ackBubbleGeometry).toHaveBeenLastCalledWith(3);
  expect(chip).toHaveStyle({ left: 272 });
  await emit('bubbleGeometry', { ...geometry, revision: 4, expanded: true, panelWidthDp: 208, panelHeightDp: 300, windowWidthDp: 304, windowHeightDp: 300 });
  expect(chip).toHaveStyle({ left: 272 });
  expect(screen.getByTestId('bubble-panel')).toHaveStyle({ left: 52, width: 208, height: 300 });
  await emit('bubbleState', { ...state, revision: 3, expanded: false });
  expect(screen.getByTestId('bubble-chip')).toBe(chip);
  expect(native.setBubbleSize).not.toHaveBeenCalled();
});

test('late initial geometry cannot replace a newer event and no ack is sent after unmount', async () => {
  let finish!: (value: BubbleGeometry | null) => void;
  native.getBubbleGeometry.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await emit('bubbleGeometry', { ...geometry, revision: 8, anchorEnd: false, chipLeftDp: 272 });
  await act(async () => { finish(geometry); }); await flush();
  expect(screen.getByTestId('bubble-chip')).toHaveStyle({ left: 272 });
  expect(native.ackBubbleGeometry).toHaveBeenCalledTimes(1);
  await screen.unmount();
  await emit('bubbleGeometry', { ...geometry, revision: 9 });
  expect(native.ackBubbleGeometry).toHaveBeenCalledTimes(1);
});

test('missing, null and failed geometry bridges retain a safe layout', async () => {
  delete Erase.getBubbleGeometry;
  delete Erase.ackBubbleGeometry;
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await emit('bubbleGeometry', geometry);
  await screen.unmount();
  native.getBubbleGeometry = jest.fn(async () => null);
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await screen.unmount();
  native.getBubbleGeometry.mockRejectedValueOnce(new Error('service unavailable'));
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  expect(screen.getByRole('button', { name: 'Cut background' })).toBeTruthy();
});


test('a service query resolving after the Bubble surface is destroyed is ignored', async () => {
  let finish!: (value: BubbleGeometry) => void;
  native.getBubbleGeometry.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await screen.unmount();
  await act(async () => { finish(geometry); }); await flush();
  expect(native.ackBubbleGeometry).not.toHaveBeenCalled();
});


test('RTL content keeps physical chip and panel coordinates instead of mirroring the overlay geometry', async () => {
  native.getLocale.mockResolvedValue({ selected: 'ar', system: 'ar' });
  native.getBubbleState.mockResolvedValue({ ...state, expanded: true });
  native.getBubbleGeometry.mockResolvedValue({ ...geometry, expanded: true });
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  expect(screen.getByTestId('bubble-surface')).toHaveStyle({ direction: 'ltr' });
  expect(screen.getByTestId('bubble-chip')).toHaveStyle({ direction: 'ltr', left: 272 });
  expect(screen.getByTestId('bubble-panel')).toHaveStyle({ direction: 'ltr', left: 0 });
  expect(screen.getByTestId('bubble-actions')).toHaveStyle({ direction: 'rtl' });
});

test('Clipboard remains available without a background probe and async errors are shown', async () => {
  native.getBubbleState.mockResolvedValue({ ...state, expanded: true });
  native.getBubbleGeometry.mockResolvedValue({ ...geometry, expanded: true, stateRevision: 1, phase: 'idle' });
  native.bubbleAction.mockResolvedValue({ ...state, revision: 2, expanded: true, readingClipboard: true });
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  expect(screen.getByRole('button', { name: 'Clipboard' })).toBeEnabled();
  await fireEvent.press(screen.getByRole('button', { name: 'Clipboard' }));
  expect(screen.getByRole('button', { name: 'Clipboard' })).toBeDisabled();
  await emit('bubbleState', { ...state, revision: 3, expanded: true, phase: 'failed', message: 'clipboard_no_focus' });
  await emit('bubbleGeometry', { ...geometry, revision: 2, expanded: true, stateRevision: 3, phase: 'failed' });
  expect(screen.getByText('Could not access the clipboard. Tap Clipboard to try again.')).toBeOnTheScreen();
  expect(screen.getByRole('button', { name: 'Clipboard' })).toBeEnabled();
});

test('in-app toggle shows a hint and never renders the panel', async () => {
  const toast = jest.spyOn(ToastAndroid, 'show');
  native.bubbleAction.mockResolvedValue({ ...state, revision: 2, actionMessage: 'bubble_other_apps' });
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Cut background' }));
  expect(screen.queryByTestId('bubble-panel')).not.toBeOnTheScreen();
  expect(toast).toHaveBeenCalledWith('Use Bubble in other apps', ToastAndroid.SHORT);
});

test('phase changes wait for matching native geometry and stale state cannot reopen a collapse', async () => {
  native.getBubbleState.mockResolvedValue({ ...state, expanded: true });
  native.getBubbleGeometry.mockResolvedValue({ ...geometry, expanded: true, stateRevision: 1, phase: 'idle' });
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await emit('bubbleState', { ...state, revision: 2, expanded: true, phase: 'copied', previewPath: '/result.png' });
  expect(screen.queryByTestId('bubble-panel')).not.toBeOnTheScreen();
  await emit('bubbleGeometry', { ...geometry, revision: 2, stateRevision: 2, expanded: true, phase: 'copied', panelHeightDp: 400 });
  expect(screen.getByTestId('bubble-panel')).toHaveStyle({ height: 400 });
  await emit('bubbleState', { ...state, revision: 3, expanded: false });
  await emit('bubbleState', { ...state, revision: 2, expanded: true });
  expect(screen.queryByTestId('bubble-panel')).not.toBeOnTheScreen();
});

test('capsule and close remain usable during a pending Save', async () => {
  const result = { ...state, expanded: true, phase: 'copied' as const, previewPath: '/result.png', editReady: true };
  native.getBubbleState.mockResolvedValue(result);
  native.getBubbleGeometry.mockResolvedValue({ ...geometry, expanded: true, phase: 'copied', panelHeightDp: 400 });
  native.bubbleAction.mockResolvedValue({ ...result, revision: 2, expanded: false });
  let finish!: (value: string) => void;
  native.saveToGallery.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Hide bubble' }));
  expect(native.hideBubble).toHaveBeenCalledTimes(1);
  await fireEvent.press(screen.getByRole('button', { name: 'Cut background' }));
  expect(native.bubbleAction).toHaveBeenCalledWith('toggle');
  await act(async () => { finish('saved'); }); await flush();
  expect(screen.queryByTestId('bubble-panel')).not.toBeOnTheScreen();
});

test('an export finishing after surface destruction cannot collapse a new Bubble session', async () => {
  native.getBubbleState.mockResolvedValue({ ...state, expanded: true, phase: 'copied', previewPath: '/result.png' });
  native.getBubbleGeometry.mockResolvedValue({ ...geometry, expanded: true, phase: 'copied', panelHeightDp: 400 });
  let finish!: (value: string) => void;
  native.saveToGallery.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
  await screen.unmount();
  native.bubbleAction.mockClear();
  await act(async () => { finish('saved'); }); await flush();
  expect(native.bubbleAction).not.toHaveBeenCalled();
});
