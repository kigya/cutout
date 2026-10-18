import { act, render, screen } from '@testing-library/react-native';
import { AccessibilityInfo, DeviceEventEmitter } from 'react-native';
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
  expect(screen.getByRole('button', { name: 'Cut background' })).toHaveStyle({ opacity: 0.7 });
  await emit('bubbleGeometry', { ...geometry, revision: 3, anchorEnd: false, chipLeftDp: 272 });
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
  await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush();
  expect(screen.getByTestId('bubble-surface')).toHaveStyle({ direction: 'ltr' });
  expect(screen.getByTestId('bubble-chip')).toHaveStyle({ direction: 'ltr', left: 272 });
  expect(screen.getByTestId('bubble-panel')).toHaveStyle({ direction: 'ltr', left: 0 });
  expect(screen.getByTestId('bubble-actions')).toHaveStyle({ direction: 'rtl' });
});
