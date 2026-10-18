import { act, fireEvent, render, renderHook, screen } from '@testing-library/react-native';
import { AccessibilityInfo, AppState, BackHandler, DeviceEventEmitter, Dimensions, I18nManager, PanResponder, Platform, type PanResponderCallbacks } from 'react-native';
import App from '../App';
import { BubbleGlyph } from '../components/BubbleGlyph';
import { AppearanceSlider } from '../components/ui/appearanceSlider';
import { LocaleGate } from '../i18n/LocaleGate';
import { normalizeBubbleAppearance, readBubbleAppearance, writeBubbleAppearance } from '../logic/bubbleAppearance';
import { Erase } from '../native/Erase';
import { BubbleAppearanceScreen } from '../screens/BubbleAppearanceScreen';
import { OnboardingScreen } from '../screens/OnboardingScreen';
import { ThemeProvider } from '../theme/ThemeProvider';
import { useSceneMotion } from './useSceneMotion';

declare const global: { __erase: Record<string, jest.Mock> };
const native = global.__erase;
const original = Dimensions.get('window');
const defaults = { sizeDp: 64, opacity: 0.7, shrinkWhenIdle: false, shrinkToDot: false };
let responders: PanResponderCallbacks[];
async function flush() { await act(async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); }); }
async function time(ms: number) { await act(async () => { jest.advanceTimersByTime(ms); }); await flush(); }
async function locale(code: string) { await act(async () => DeviceEventEmitter.emit('eraseLocale', { selected: code, system: code })); }
function frame(width: number, height: number) { Dimensions.set({ window: { width, height, scale: 1, fontScale: 1 }, screen: { width, height, scale: 1, fontScale: 1 } }); }
function wrap(child: React.ReactNode) { return <ThemeProvider mode="light"><LocaleGate>{child}</LocaleGate></ThemeProvider>; }
async function motion(reduced: boolean) { await act(async () => {
  for (const [name, listener] of (AccessibilityInfo.addEventListener as jest.Mock).mock.calls) if (name === 'reduceMotionChanged') listener(reduced);
}); }
async function activity(value: string) { await act(async () => {
  for (const [name, listener] of (AppState.addEventListener as jest.Mock).mock.calls) if (name === 'change') listener(value);
}); }
function gesture(config: PanResponderCallbacks, name: keyof PanResponderCallbacks, dx = 0, dy = 0, x = 80) {
  return (config[name] as Function)({ nativeEvent: { pageX: x, locationX: x } }, { dx, dy });
}

beforeEach(() => {
  jest.useFakeTimers(); jest.clearAllMocks(); responders = [];
  Platform.OS = 'android'; I18nManager.isRTL = false; AppState.currentState = 'active'; frame(390, 844);
  delete Erase.getMainActivityState;
  native.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  native.getOnboardingCompleted.mockResolvedValue(true); native.completeOnboarding.mockResolvedValue(undefined);
  native.getBubbleAppearance.mockResolvedValue(defaults);
  native.setBubbleAppearance.mockImplementation(async (sizeDp, opacity, shrinkWhenIdle, shrinkToDot) => ({ sizeDp, opacity, shrinkWhenIdle, shrinkToDot }));
  jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
  jest.spyOn(AccessibilityInfo, 'addEventListener').mockImplementation(() => ({ remove: jest.fn() }));
  jest.spyOn(AppState, 'addEventListener').mockImplementation(() => ({ remove: jest.fn() }));
  jest.spyOn(PanResponder, 'create').mockImplementation(config => { responders.push(config); return { panHandlers: {} as ReturnType<typeof PanResponder.create>["panHandlers"], getInteractionHandle: () => undefined }; });
});
afterEach(() => { jest.useRealTimers(); jest.restoreAllMocks(); Platform.OS = 'android'; I18nManager.isRTL = false; Dimensions.set({ window: original, screen: original }); });

test('appearance normalization and cross-route writes persist in order and recover from disk failure', async () => {
  expect(normalizeBubbleAppearance({})).toEqual(defaults);
  expect(normalizeBubbleAppearance({ sizeDp: 49, opacity: 0.21, shrinkWhenIdle: true, shrinkToDot: true })).toEqual({ sizeDp: 48, opacity: 0.2, shrinkWhenIdle: true, shrinkToDot: true });
  expect(normalizeBubbleAppearance({ sizeDp: 1000, opacity: 2 })).toEqual({ ...defaults, sizeDp: 88, opacity: 1 });
  expect(normalizeBubbleAppearance({ sizeDp: -10, opacity: -1 })).toEqual({ ...defaults, sizeDp: 48, opacity: 0.2 });
  expect(normalizeBubbleAppearance({ sizeDp: NaN, opacity: Infinity })).toEqual(defaults);
  native.setBubbleAppearance.mockRejectedValueOnce(new Error('disk full'));
  await expect(writeBubbleAppearance(defaults)).rejects.toThrow('disk full');
  const next = { ...defaults, sizeDp: 80 };
  await writeBubbleAppearance(next);
  expect(native.setBubbleAppearance).toHaveBeenLastCalledWith(80, 0.7, false, false);
  expect(await readBubbleAppearance()).toEqual(defaults);
});

test('sliders quantize touch and accessibility input, mirror RTL and commit cancellations', async () => {
  const change = jest.fn(), commit = jest.fn();
  const slider = <AppearanceSlider label="Size" value={64} min={48} max={88} step={4} format={String} onChange={change} onCommit={commit} />;
  await render(wrap(slider));
  await fireEvent(screen.getByRole('adjustable'), 'layout', { nativeEvent: { layout: { width: 240 } } });
  let c = responders[responders.length - 1];
  expect(gesture(c, 'onStartShouldSetPanResponder')).toBe(true);
  expect(gesture(c, 'onPanResponderTerminationRequest')).toBe(false);
  await act(() => { gesture(c, 'onPanResponderGrant', 0, 0, 120); gesture(c, 'onPanResponderMove', 0, 0, 1000); gesture(c, 'onPanResponderRelease'); });
  expect(commit).toHaveBeenLastCalledWith(88);
  await act(() => { gesture(c, 'onPanResponderMove', 0, 0, -50); gesture(c, 'onPanResponderTerminate'); });
  expect(commit).toHaveBeenLastCalledWith(48);
  await fireEvent(screen.getByRole('adjustable'), 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
  expect(commit).toHaveBeenLastCalledWith(68);
  await fireEvent(screen.getByRole('adjustable'), 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } });
  expect(commit).toHaveBeenLastCalledWith(60);
  I18nManager.isRTL = true;
  await screen.rerender(wrap(slider));
  c = responders[responders.length - 1];
  await act(() => { gesture(c, 'onPanResponderGrant', 0, 0, 0); gesture(c, 'onPanResponderRelease'); });
  expect(commit).toHaveBeenLastCalledWith(88);
});

test('size settings show bare numbers, save switches, preview idle/dot and restore defaults', async () => {
  const back = jest.fn(); await render(wrap(<BubbleAppearanceScreen mode="size" onBack={back} />)); await flush();
  expect(screen.getByText('64')).toBeOnTheScreen();
  expect(screen.queryByText(/64 dp/)).not.toBeOnTheScreen();
  await fireEvent(screen.getByRole('adjustable'), 'accessibilityAction', { nativeEvent: { actionName: 'increment' } }); await flush();
  expect(screen.getByText('68')).toBeOnTheScreen();
  await fireEvent(screen.getByRole('switch', { name: 'Shrink when idle' }), 'valueChange', true); await flush();
  await fireEvent(screen.getByRole('switch', { name: 'Shrink to a dot' }), 'valueChange', true); await flush();
  await time(3000);
  await fireEvent.press(screen.getByRole('button', { name: 'Bubble preview' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Reset' })); await flush();
  expect(native.setBubbleAppearance).toHaveBeenLastCalledWith(64, 0.7, false, false);
  expect(screen.getByRole('switch', { name: 'Shrink to a dot' })).toBeDisabled();
  await fireEvent.press(screen.getByRole('button', { name: 'Back' })); expect(back).toHaveBeenCalled();
});

test('opacity settings save the percentage and report rejected writes without losing the preview', async () => {
  await render(wrap(<BubbleAppearanceScreen mode="opacity" onBack={jest.fn()} />)); await flush();
  expect(screen.getByText('70%')).toBeOnTheScreen();
  native.setBubbleAppearance.mockRejectedValueOnce(new Error('disk full'));
  await fireEvent(screen.getByRole('adjustable'), 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } }); await flush();
  expect(screen.getByText('65%')).toBeOnTheScreen(); expect(screen.getByText('Could not save')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Reset' })); await flush();
  expect(native.setBubbleAppearance).toHaveBeenLastCalledWith(64, 0.7, false, false);
});

test('settings retry failed reads and ignore native responses or errors after leaving the screen', async () => {
  native.getBubbleAppearance.mockRejectedValueOnce(new Error('read'));
  await render(wrap(<BubbleAppearanceScreen mode="size" onBack={jest.fn()} />)); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Try again' })); await flush(); expect(screen.getByText('64')).toBeOnTheScreen();
  let reject!: (value: Error) => void;
  native.setBubbleAppearance.mockImplementationOnce(() => new Promise((_, no) => { reject = no; }));
  await fireEvent.press(screen.getByRole('button', { name: 'Reset' })); await flush(); await screen.unmount();
  await act(() => reject(new Error('closed'))); await flush();
  let finish!: (value: typeof defaults) => void;
  native.getBubbleAppearance.mockImplementationOnce(() => new Promise(yes => { finish = yes; }));
  await render(wrap(<BubbleAppearanceScreen mode="opacity" onBack={jest.fn()} />)); await flush(); await screen.unmount();
  await act(() => finish(defaults)); await flush();
  native.getBubbleAppearance.mockImplementationOnce(() => new Promise((_, no) => { reject = no; }));
  await render(wrap(<BubbleAppearanceScreen mode="size" onBack={jest.fn()} />)); await flush(); await screen.unmount();
  await act(() => reject(new Error('closed'))); await flush();
});

test('onboarding advances manually, supports Back/swipes/dots, and retries completion failure', async () => {
  const complete = jest.fn().mockRejectedValueOnce(new Error('disk')).mockResolvedValue(undefined);
  let back!: () => boolean;
  jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_name, callback) => { back = callback as () => boolean; return { remove: jest.fn() }; });
  await render(wrap(<OnboardingScreen onComplete={complete} />)); await flush();
  expect(screen.getByText('Keep what matters')).toBeOnTheScreen(); expect(back()).toBe(false);
  const c = responders[0]; expect(gesture(c, 'onMoveShouldSetPanResponder', 0, 50)).toBe(false); expect(gesture(c, 'onMoveShouldSetPanResponder', 60, 0)).toBe(true);
  await act(() => gesture(c, 'onPanResponderRelease', 10));
  await act(() => gesture(c, 'onPanResponderRelease', -80)); await flush();
  await act(() => gesture(c, 'onPanResponderRelease', 80)); await flush();
  await act(() => gesture(c, 'onPanResponderRelease', -80)); await flush();
  expect(screen.getByText('Copy. Cut out. Paste.')).toBeOnTheScreen();
  await act(() => { expect(back()).toBe(true); }); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Page 3 / 3' })); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Get started' })); await flush();
  expect(screen.getByText('Could not save')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Get started' })); expect(complete).toHaveBeenCalledTimes(2);
});

test('onboarding walkthrough is interactive and never reads the clipboard or requests permissions', async () => {
  await render(wrap(<OnboardingScreen onComplete={jest.fn()} />)); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Next' })); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Next' })); await flush();
  await time(1600); expect(screen.getByRole('button', { name: 'Clipboard' })).toBeOnTheScreen();
  await time(3200); expect(screen.queryByRole('button', { name: 'Clipboard' })).not.toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Tap the Bubble' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Clipboard' })); await time(1100);
  expect(screen.getByRole('button', { name: 'Cut another' })).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Cut another' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Gallery' }));
  await activity('background'); await activity('active'); await flush();
  expect(screen.getByRole('button', { name: 'File' })).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'File' }));
  await screen.unmount(); await time(1200);
  expect(native.cutClipboard).not.toHaveBeenCalled(); expect(native.openPermission).not.toHaveBeenCalled();
});

test('onboarding adapts to narrow/landscape, themes, RTL and iOS without a fake system Bubble', async () => {
  await act(() => frame(320, 640));
  await render(wrap(<OnboardingScreen onComplete={jest.fn()} />)); await flush(); await motion(true);
  await fireEvent.press(screen.getByRole('button', { name: 'Page 3 / 3' })); await flush();
  await motion(true);
  await fireEvent.press(screen.getByRole('button', { name: 'Tap the Bubble' })); await fireEvent.press(screen.getByRole('button', { name: 'Clipboard' })); await time(100);
  I18nManager.isRTL = true; await locale('ar');
  await act(() => gesture(responders[0], 'onPanResponderRelease', 80)); await flush();
  await act(() => frame(1000, 600));
  await screen.rerender(<ThemeProvider mode="dark"><LocaleGate><OnboardingScreen onComplete={jest.fn()} /></LocaleGate></ThemeProvider>); await flush();
  Platform.OS = 'ios';
  await screen.unmount();
  await render(wrap(<OnboardingScreen onComplete={jest.fn()} />)); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Page 3 / 3' })); await flush();
  expect(screen.getByText('Ready for your next idea')).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: 'Tap the Bubble' })).not.toBeOnTheScreen();
});

test('scene motion stops in the background and after unmount, and glyph keeps a full-size hit target outside itself', async () => {
  const { result } = await renderHook(() => useSceneMotion()); await flush();
  expect(result.current.active).toBe(true); await activity('background'); expect(result.current.active).toBe(false);
  await motion(true); expect(result.current.reduced).toBe(true); await activity('active'); await motion(false);
  await screen.unmount();
  let finish!: (value: boolean) => void;
  jest.mocked(AccessibilityInfo.isReduceMotionEnabled).mockImplementationOnce(() => new Promise(yes => { finish = yes; }));
  await renderHook(() => useSceneMotion(1000)); await screen.unmount(); await act(() => finish(true));
  await render(wrap(<BubbleGlyph />));
  await screen.rerender(wrap(<BubbleGlyph size={88} compact dot reducedMotion />));
  await screen.rerender(wrap(<BubbleGlyph size={48} compact />));
});

test('App retries the onboarding read, persists completion, and exposes both appearance settings routes', async () => {
  native.getOnboardingCompleted.mockRejectedValueOnce(new Error('read')).mockResolvedValue(false);
  await render(<App />); await flush(); await time(1500);
  await fireEvent.press(screen.getByRole('button', { name: 'Try again' })); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Page 3 / 3' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Get started' })); await flush();
  expect(native.completeOnboarding).toHaveBeenCalledTimes(1);
  await fireEvent.press(screen.getByRole('button', { name: 'Settings' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Bubble size' })); await flush();
  expect(screen.getByText('64')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Bubble opacity' })); await flush();
  expect(screen.getByText('70%')).toBeOnTheScreen(); await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
});


test('dark photo and clipboard scenes keep readable artwork; rapid tutorial/finish taps are accepted once', async () => {
  await render(<ThemeProvider mode="dark"><LocaleGate><OnboardingScreen onComplete={jest.fn()} /></LocaleGate></ThemeProvider>); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Next' })); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Next' })); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Tap the Bubble' }));
  const action = screen.getByRole('button', { name: 'Clipboard' });
  await act(() => { action.props.onClick(); action.props.onClick(); });
  await time(1100); expect(screen.getByRole('button', { name: 'Cut another' })).toBeOnTheScreen();
  await screen.unmount();
  let finish!: () => void;
  const complete = jest.fn(() => new Promise<void>(yes => { finish = yes; }));
  await render(wrap(<OnboardingScreen onComplete={complete} />)); await flush();
  await fireEvent.press(screen.getByRole('button', { name: 'Page 3 / 3' }));
  const start = screen.getByRole('button', { name: 'Get started' });
  await act(() => { start.props.onClick(); start.props.onClick(); });
  expect(complete).toHaveBeenCalledTimes(1);
  await act(() => finish());
});


test('onboarding storage can remain pending after splash without exposing Home', async () => {
  let finish!: (value: boolean) => void;
  native.getOnboardingCompleted.mockImplementationOnce(() => new Promise(yes => { finish = yes; }));
  await render(<App />); await flush(); await time(1500);
  expect(screen.queryByRole('button', { name: 'Settings' })).not.toBeOnTheScreen();
  await act(() => finish(true)); await flush();
  expect(screen.getByRole('button', { name: 'Settings' })).toBeOnTheScreen();
});
