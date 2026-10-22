import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AccessibilityInfo, DeviceEventEmitter } from 'react-native';
import { LocaleGate } from '../i18n/LocaleGate';
import { type BubbleAppearance, type BubbleSnapshot } from '../native/Erase';
import { BubbleApp } from './BubbleApp';

declare const global: { __erase: Record<string, jest.Mock> };
const native = global.__erase;
const state: BubbleSnapshot = { revision: 1, expanded: false, anchorEnd: true, phase: 'idle', clipboardReady: false, readingClipboard: false, message: null, previewPath: null, rasterizedSvg: false, editReady: false, percent: 0 };
const appearance = { sizeDp: 64, opacity: 0.7, shrinkWhenIdle: true, shrinkToDot: true };
async function flush() { await act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); }); }
async function emit(name: string, value: unknown) { await act(() => DeviceEventEmitter.emit(name, value)); await flush(); }
async function time(ms: number) { await act(() => jest.advanceTimersByTime(ms)); await flush(); }
async function mount() { await render(<LocaleGate><BubbleApp /></LocaleGate>); await flush(); }
beforeEach(() => {
  jest.useFakeTimers(); jest.clearAllMocks();
  AccessibilityInfo.isReduceMotionEnabled = jest.fn(async () => false);
  native.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  native.getBubbleState.mockResolvedValue(state); native.getBubbleGeometry.mockResolvedValue(null);
  native.getBubbleAppearance.mockResolvedValue(appearance);
});
afterEach(() => { jest.useRealTimers(); jest.restoreAllMocks(); });

test('idle shrinking preserves native hit geometry, pauses during drag/processing/clipboard/export and applies live settings', async () => {
  await mount(); await time(3000);
  expect(screen.getByTestId('bubble-chip')).toHaveStyle({ width: 72, height: 72 });
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ transform: [{ scale: 12 / 64 }] });
  await emit('bubbleInteraction', { active: true }); await time(3000);
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ transform: [{ scale: 1 }] });
  await emit('bubbleInteraction', { active: false });
  await emit('bubbleState', { ...state, revision: 2, phase: 'working' }); await time(3000);
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ transform: [{ scale: 1 }] });
  await emit('bubbleState', { ...state, revision: 3, readingClipboard: true }); await time(3000);
  await emit('bubbleState', { ...state, revision: 4, phase: 'copied', expanded: true, previewPath: '/cut' });
  let finish!: (value: string) => void;
  native.saveToGallery.mockImplementationOnce(() => new Promise(yes => { finish = yes; }));
  await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
  await emit('bubbleState', { ...state, revision: 5, phase: 'copied', previewPath: '/cut' }); await time(3000);
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ transform: [{ scale: 1 }] });
  native.bubbleAction.mockResolvedValue({ ...state, revision: 6 });
  await act(() => finish('saved')); await flush();
  await emit('bubbleAppearance', { ...appearance, sizeDp: 88, opacity: 0.5, shrinkToDot: false }); await time(3000);
  expect(screen.getByTestId('bubble-chip')).toHaveStyle({ width: 96 });
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ opacity: 0.5, transform: [{ scale: 32 / 88 }] });
});

test('an appearance query cannot overwrite a newer event or update an unmounted surface; failed reads retain defaults', async () => {
  let finish!: (value: BubbleAppearance) => void;
  native.getBubbleAppearance.mockImplementationOnce(() => new Promise(yes => { finish = yes; }));
  await mount(); await emit('bubbleAppearance', { ...appearance, opacity: 0.4 });
  await act(() => finish({ ...appearance, opacity: 1 })); await flush();
  expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ opacity: 0.4 }); await screen.unmount();
  native.getBubbleAppearance.mockImplementationOnce(() => new Promise(yes => { finish = yes; }));
  await mount(); await screen.unmount(); await act(() => finish(appearance)); await flush();
  native.getBubbleAppearance.mockRejectedValueOnce(new Error('disk'));
  await mount(); expect(screen.getByTestId('bubble-artwork')).toHaveStyle({ opacity: 0.7 });
});

test.each(['gallery', 'copy', 'save', 'collapse'] as const)('late %s success or rejection cannot affect a destroyed surface', async action => {
  const result = { ...state, expanded: true, phase: 'copied', previewPath: '/cut', editReady: true };
  native.getBubbleState.mockResolvedValue(action === 'gallery' ? { ...state, expanded: true } : result);
  let finish!: (value: any) => void, reject!: (error: Error) => void;
  const pending = () => new Promise((yes, no) => { finish = yes; reject = no; });
  if (action === 'gallery') native.bubbleAction.mockImplementationOnce(pending);
  else if (action === 'copy') native.publishPng.mockImplementationOnce(pending);
  else if (action === 'save') native.saveToGallery.mockImplementationOnce(pending);
  else { native.publishPng.mockResolvedValue('copied'); native.bubbleAction.mockImplementationOnce(pending); }
  await mount();
  await fireEvent.press(screen.getByRole('button', { name: action === 'gallery' ? 'Gallery' : action === 'save' ? 'Save' : 'Copy' })); await flush();
  await screen.unmount();
  await act(() => finish(action === 'gallery' || action === 'collapse' ? { ...state, revision: 3 } : 'copied')); await flush();
  if (action !== 'collapse') {
    if (action === 'gallery') native.bubbleAction.mockImplementationOnce(pending);
    if (action === 'copy') native.publishPng.mockImplementationOnce(pending);
    if (action === 'save') native.saveToGallery.mockImplementationOnce(pending);
    await mount(); await fireEvent.press(screen.getByRole('button', { name: action === 'gallery' ? 'Gallery' : action === 'save' ? 'Save' : 'Copy' })); await screen.unmount();
    await act(() => reject(new Error('closed'))); await flush();
  }
});

test('geometry without legacy revision metadata still shows the matching panel and capsule opens it', async () => {
  const legacy = { ...state, revision: undefined, expanded: true };
  native.getBubbleState.mockResolvedValue(legacy);
  native.getBubbleGeometry.mockResolvedValue({ revision: 1, expanded: true, anchorEnd: true, windowWidthDp: 344, windowHeightDp: 260, chipSizeDp: 72, panelWidthDp: 260, panelHeightDp: 260, chipLeftDp: 272, chipTopDp: 0, surfaceWidthDp: 640, surfaceHeightDp: 400 });
  native.bubbleAction.mockResolvedValue(legacy);
  await mount(); await fireEvent.press(screen.getByRole('button', { name: 'Cut background' }));
  expect(screen.getByTestId('bubble-panel')).toBeOnTheScreen();
});
