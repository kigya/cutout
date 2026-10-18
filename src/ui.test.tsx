import { act, render, screen, userEvent } from '@testing-library/react-native';
import { AccessibilityInfo, AppState, BackHandler, DeviceEventEmitter, Dimensions, I18nManager, Linking, PermissionsAndroid, Platform, Text, ToastAndroid } from 'react-native';
import { base64ToBytes, bytesToBase64 } from './logic/bytes';
import App from './App';
import { BubbleApp } from './bubble/BubbleApp';
import { BubbleDemo } from './components/BubbleDemo';
import { SplashScreen } from './components/SplashScreen';
import { SweepPreview } from './components/SweepPreview';
import { LocaleGate, useLocale } from './i18n/LocaleGate';
import { text } from './i18n/catalogs';
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { Notice } from './components/ui/notice';
import { PathText } from './components/ui/pathText';
import { Screen } from './components/ui/screen';
import { Sheet } from './components/ui/sheet';
import { EditorScreen } from './screens/EditorScreen';
import { HomeScreen } from './screens/HomeScreen';
import { LanguageScreen } from './screens/LanguageScreen';
import { ProcessingScreen } from './screens/ProcessingScreen';
import { ResultScreen } from './screens/ResultScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { ThemeProvider } from './theme/ThemeProvider';

declare const global: {
  __modalDismisses: Array<() => void>;
  __erase: Record<string, jest.Mock>;
  __gestures: Array<{ handlers: Record<string, (event?: unknown) => void> }>;
  __gestureCompositions: Array<{ kind: string; gestures: Array<{ kind: string; pointerCount?: number; handlers: Record<string, (event?: unknown, success?: boolean) => void> }> }>;
  __shared: Array<{ value: number }>;
  __shader: { allow: () => void; block: () => void };
  __imageSize: { set: (width: number, height: number) => void };
  __imageAlpha: { set: (alpha: ArrayLike<number> | null) => void };
  __imagePixels: { set: (pixels: ArrayLike<number> | null | undefined) => void };
  __makeImage: { set: (allow: boolean) => void };
  __imageAvailability: { set: (available: boolean) => void };
  __maskImages: Array<{ info: { width: number; height: number; colorType: number; alphaType: number }; pixels: Uint8Array; bytesPerRow: number }>;
  __skiaGroups: Array<Record<string, unknown>>;
  __safeMetrics: { current: unknown };
  __DEV__: boolean;
};

const erase = global.__erase as Record<string, jest.Mock>;
const gestures = global.__gestures as Array<{ handlers: Record<string, (event?: unknown) => void> }>;
const compositions = global.__gestureCompositions;
const shared = global.__shared as Array<{ value: number }>;

const home = {
  rows: [
    { kind: 'overlay' as const, title: 'Overlay', status: 'granted' as const, need: 'requiredForBubble' as const },
    { kind: 'backgroundPopup' as const, title: 'Popup', status: 'denied' as const, need: 'optional' as const },
    { kind: 'autostart' as const, title: 'Autostart', status: 'unknown' as const, need: 'optional' as const },
    { kind: 'battery' as const, title: 'Battery', status: 'granted' as const, need: 'optional' as const },
    { kind: 'notifications' as const, title: 'Notifications', status: 'denied' as const, need: 'optional' as const },
  ],
  bubbleEnabled: false,
  showXiaomiTip: false,
  clipboardReady: false,
};

const bubble = {
  expanded: true,
  anchorEnd: true,
  phase: 'idle' as const,
  clipboardReady: false,
  readingClipboard: false,
  message: null,
  previewPath: null,
  rasterizedSvg: false,
  editReady: false,
  percent: 0,
};

async function flush() {
  await act(async () => {
    for (let step = 0; step < 8; step += 1) await Promise.resolve();
  });
}

async function emitDevice(name: string, payload: unknown) {
  await act(async () => {
    DeviceEventEmitter.emit(name, payload);
    await Promise.resolve();
  });
}

async function dismissSourceModal() {
  while (global.__modalDismisses.length) {
    const dismiss = global.__modalDismisses.shift()!;
    await act(async () => { dismiss(); });
  }
}

function readySession(status: 'ready' | 'manual' | 'svg' | 'failed' = 'ready') {
  return { status, message: status, originalPath: '/o.png', cutoutPath: '/c.png', width: 4, height: 4 };
}

afterEach(() => {
  jest.useRealTimers();
  jest.restoreAllMocks();
});

beforeEach(() => {
  for (const mock of Object.values(erase)) mock.mockReset();
  delete erase.getPreferences;
  delete erase.setPreferences;
  delete erase.logProductEvent;
  erase.setBubble.mockResolvedValue(undefined);
  erase.hideBubble.mockResolvedValue(undefined);
  erase.openPermission.mockResolvedValue(undefined);
  erase.cancelCut.mockResolvedValue(undefined);
  erase.setLocale.mockResolvedValue(undefined);
  erase.restart.mockResolvedValue(undefined);
  erase.settleBubble.mockResolvedValue(true);
  jest.spyOn(ToastAndroid, 'show').mockImplementation(() => undefined);
  global.__modalDismisses.length = 0;
  AppState.currentState = 'active';
  AppState.addEventListener = jest.fn(() => ({ remove: jest.fn() }));
  AccessibilityInfo.isReduceMotionEnabled = jest.fn(async () => false);
  gestures.length = 0;
  compositions.length = 0;
  shared.length = 0;
  global.__maskImages.length = 0;
  global.__skiaGroups.length = 0;
  global.__shader.allow();
  global.__imageSize.set(120, 80);
  global.__imageAlpha.set(null);
  global.__imagePixels.set(undefined);
  global.__makeImage.set(true);
  global.__imageAvailability.set(true);
  global.__safeMetrics.current = {
    insets: { top: 1, right: 2, bottom: 3, left: 4 },
    frame: { x: 0, y: 0, width: 800, height: 1200 },
  };
  erase.getHome.mockResolvedValue(home);
  erase.prepareModel.mockResolvedValue('ready');
  erase.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  erase.consumePendingEdit.mockResolvedValue(null);
  erase.getBubbleState.mockResolvedValue({ ...bubble, expanded: false });
  erase.getBubbleGeometry.mockResolvedValue(null);
  erase.bubbleAction.mockResolvedValue({ ...bubble, message: 'copied' });
  erase.readAlpha.mockResolvedValue(bytesToBase64(Uint8Array.from({ length: 16 }, () => 255)));
  erase.writeMasked.mockResolvedValue({ status: 'ready', path: '/masked.png' });
  erase.publishPng.mockResolvedValue('copied');
  erase.saveToGallery.mockResolvedValue('saved');
  erase.pickAndCut.mockResolvedValue(readySession());
  erase.cutClipboard.mockResolvedValue(readySession());
  erase.cutFixture.mockResolvedValue(readySession());
  Platform.OS = 'ios';
});

test('small controls cover every tone and an empty notice', async () => {
  await render(
    <LocaleGate>
      <Screen>
        <Button label="Primary" onPress={() => undefined} />
        <Button label="Ghost" tone="ghost" onPress={() => undefined} />
        <Button label="Off" disabled onPress={() => undefined} />
        <Badge label="N" />
        <Badge label="G" tone="good" />
        <Badge label="W" tone="warn" />
        <Notice message="" />
        <Notice message="Saved to gallery" />
        <PathText text="Security → Boost ← back" />
        <PathText text="plain" />
      </Screen>
    </LocaleGate>,
  );
  expect(screen.getByText('Saved to gallery')).toBeTruthy();
  expect(screen.getByText('plain')).toBeTruthy();
});

test('theme context can omit a status bar for embedded surfaces', async () => {
  await render(<ThemeProvider statusBar={false}><Text>Embedded panel</Text></ThemeProvider>);
  expect(screen.getByText('Embedded panel')).toBeTruthy();
});

test('reduced motion settles splash, notice, and sheet transitions', async () => {
  const listeners = jest.spyOn(AccessibilityInfo, 'addEventListener');
  const splash = await render(<SplashScreen onDone={() => undefined} />);
  await flush();
  const splashValues = shared.slice(-2);
  const splashMotion = listeners.mock.calls.at(-1)![1] as (value: boolean) => void;
  await act(async () => { splashMotion(true); });
  expect(splashValues.map(value => value.value)).toEqual([1, 1]);
  await splash.unmount();

  const notice = await render(<Notice message="Settings saved" />);
  const noticeMotion = listeners.mock.calls.at(-1)![1] as (value: boolean) => void;
  await act(async () => { noticeMotion(true); });
  expect(screen.getByRole('alert')).toBeTruthy();
  await notice.unmount();

  const sheet = await render(<Sheet title="Details" body="A clear explanation" visible closeLabel="OK" onClose={() => undefined} />);
  const sheetMotion = listeners.mock.calls.at(-1)![1] as (value: boolean) => void;
  await act(async () => { sheetMotion(true); });
  expect(screen.getByText('A clear explanation')).toBeTruthy();
  await sheet.unmount();
}, 15000);

test('animated surfaces ignore a system motion preference that resolves after unmount', async () => {
  const resolvers: Array<(value: boolean) => void> = [];
  const preference = jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockImplementation(
    () => new Promise(resolve => { resolvers.push(resolve); }),
  );
  const view = await render(
    <LocaleGate>
      <BubbleApp />
      <BubbleDemo onDismiss={() => undefined} />
      <SweepPreview path="/late-motion.png" width={220} height={112} />
      <SplashScreen onDone={() => undefined} />
      <Notice message="Saved" />
      <Sheet title="Details" body="Body" visible closeLabel="OK" onClose={() => undefined} />
    </LocaleGate>,
  );
  expect(resolvers).toHaveLength(6);
  await view.unmount();
  await act(async () => {
    resolvers.forEach(resolve => resolve(true));
    await Promise.resolve();
  });
  preference.mockRestore();
});

test('the sheet rises when it is open and stays put when it is closed', async () => {
  const onClose = jest.fn();
  const closed = await render(
    <LocaleGate>
      <Sheet title="Hidden" body="" visible={false} closeLabel="OK" onClose={onClose} />
    </LocaleGate>,
  );
  await closed.unmount();
  await render(
    <LocaleGate>
      <Sheet title="How it works" body="Local" visible closeLabel="OK" onClose={onClose} />
    </LocaleGate>,
  );
  await userEvent.press(screen.getByText('How it works'));
  await userEvent.press(screen.getByRole('button', { name: 'OK' }));
  expect(onClose).toHaveBeenCalledTimes(1);
}, 15000);

test('a missing system inset still renders the active processing stage', async () => {
  global.__safeMetrics.current = null;
  await render(
    <LocaleGate>
      <Screen scroll>
        <ProcessingScreen previewPath={null} percent={4} stage="preparing" onCancel={() => undefined} />
      </Screen>
    </LocaleGate>,
  );
  expect(screen.getByText('Preparing the image…')).toBeTruthy();
});

test('the sweep uses a fixed frame, the window frame, and skips a blank image', async () => {
  const { unmount } = await render(
    <LocaleGate>
      <SweepPreview path="/a.jpg" width={220} height={112} />
      <SweepPreview path="/a.jpg" windowWidth={400} windowHeight={800} />
    </LocaleGate>,
  );
  await unmount();
  global.__imageSize.set(0, 80);
  await render(<SweepPreview path="/a.jpg" width={10} height={10} />);
  global.__imageSize.set(80, 0);
  await render(<SweepPreview path="/a.jpg" width={10} height={10} />);
  global.__imageSize.set(120, 80);
  await render(<SweepPreview path={null} />);
});

test('the sweep follows app activity and keeps running over the system bubble', async () => {
  const appStateListener = jest.spyOn(AppState, 'addEventListener');
  const motionListener = jest.spyOn(AccessibilityInfo, 'addEventListener');
  await render(<SweepPreview path="/lifecycle.jpg" width={220} height={112} />);
  await flush();
  expect(screen.getByTestId('canvas')).toBeTruthy();
  const clock = shared.at(-1)!;
  const appStateChange = appStateListener.mock.calls.at(-1)![1] as (state: string) => void;
  const reducedMotionChange = motionListener.mock.calls.at(-1)![1] as (value: boolean) => void;

  await act(async () => { appStateChange('background'); });
  expect(clock.value).toBe(0);
  await act(async () => { appStateChange('active'); });
  expect(clock.value).toBe(1);
  await act(async () => { reducedMotionChange(true); });
  expect(clock.value).toBe(0);
  await screen.unmount();

  AppState.currentState = 'background';
  await render(<SweepPreview path="/overlay.jpg" width={220} height={112} overlayVisible />);
  await flush();
  expect(shared.at(-1)?.value).toBe(1);
  AppState.currentState = 'active';
});

test('bubble demo responds to lifecycle, reduced motion, and dismissal', async () => {
  const originalState = AppState.currentState;
  AppState.currentState = 'background';
  const appStateListener = jest.spyOn(AppState, 'addEventListener');
  const motionListener = jest.spyOn(AccessibilityInfo, 'addEventListener');
  const onDismiss = jest.fn();
  await render(<LocaleGate><BubbleDemo onDismiss={onDismiss} /></LocaleGate>);
  await flush();
  const drift = shared.at(-1)!;
  const appStateChange = appStateListener.mock.calls.at(-1)![1] as (state: string) => void;
  const reducedMotionChange = motionListener.mock.calls.at(-1)![1] as (value: boolean) => void;
  expect(drift.value).toBe(0);
  await act(async () => { appStateChange('active'); });
  expect(drift.value).toBe(1);
  await act(async () => { reducedMotionChange(true); });
  expect(drift.value).toBe(0);
  await userEvent.press(screen.getByRole('button', { name: 'OK' }));
  expect(onDismiss).toHaveBeenCalledTimes(1);
  await screen.unmount();
  AppState.currentState = originalState;
});

test('splash finishes once and cancels when it unmounts', async () => {
  jest.useFakeTimers();
  const done = jest.fn();
  const first = await render(<SplashScreen onDone={done} />);
  await act(async () => { jest.advanceTimersByTime(1499); });
  expect(done).not.toHaveBeenCalled();
  await act(async () => { jest.advanceTimersByTime(1); });
  expect(done).toHaveBeenCalledTimes(1);
  await first.unmount();
  const early = jest.fn();
  const second = await render(<SplashScreen onDone={early} />);
  await second.unmount();
  await act(async () => { jest.advanceTimersByTime(1000); });
  expect(early).not.toHaveBeenCalled();
});

test('home disables clipboard until a picture is there and explains each permission', async () => {
  const onCut = jest.fn();
  const onRefresh = jest.fn();
  const onSettings = jest.fn();
  await render(
    <LocaleGate>
      <HomeScreen home={home} busy={false} notice="" onRefresh={onRefresh} onSettings={onSettings} onCut={onCut} />
    </LocaleGate>,
  );
  await userEvent.press(screen.getByRole('button', { name: 'Settings' }));
  expect(onSettings).toHaveBeenCalled();
  await userEvent.press(screen.getByRole('button', { name: 'Cut here' }));
  await userEvent.press(screen.getByText('Choose a picture'));
  expect(screen.getByRole('button', { name: 'Gallery' })).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'Back' }));
  await userEvent.press(screen.getByRole('button', { name: 'Cut here' }));
  expect(screen.getByRole('button', { name: 'Clipboard' })).toBeDisabled();
  expect(onCut).not.toHaveBeenCalled();
  await userEvent.press(screen.getByRole('button', { name: 'Gallery' }));
  await dismissSourceModal();
  await userEvent.press(screen.getByRole('button', { name: 'Cut here' }));
  await userEvent.press(screen.getByRole('button', { name: 'File' }));
  await dismissSourceModal();
  expect(onCut).toHaveBeenCalledWith('gallery');
  expect(onCut).toHaveBeenCalledWith('file');
  await userEvent.press(screen.getByRole('button', { name: 'Why this is needed: Windows from the background' }));
  expect(screen.getByText(/gallery may not open/)).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'OK' }));
}, 15000);

test('home omits the permissions section when the native snapshot has no permission rows', async () => {
  await render(
    <LocaleGate>
      <HomeScreen home={{ ...home, rows: [] }} busy={false} notice="" onRefresh={() => undefined} onSettings={() => undefined} onCut={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.getByText('Cutout')).toBeTruthy();
  expect(screen.queryByText('Permissions')).toBeNull();
});

test('returning to Home refreshes permissions when bubble setup is not pending', async () => {
  let stateHandler: ((state: string) => void | Promise<void>) | undefined;
  const subscription = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    stateHandler = listener as (state: string) => void | Promise<void>;
    return { remove: () => undefined };
  });
  const onRefresh = jest.fn();
  await render(
    <LocaleGate>
      <HomeScreen home={home} busy={false} notice="" onRefresh={onRefresh} onSettings={() => undefined} onCut={() => undefined} />
    </LocaleGate>,
  );
  onRefresh.mockClear();
  await act(async () => { await stateHandler?.('active'); });
  expect(onRefresh).toHaveBeenCalledTimes(1);
  expect(erase.getHome).not.toHaveBeenCalled();
  subscription.mockRestore();
});

test('home hides bubble controls off Android and blocks source selection while busy', async () => {
  const onCut = jest.fn();
  const view = await render(
    <LocaleGate>
      <HomeScreen home={home} busy notice="Working" onRefresh={() => undefined} onSettings={() => undefined} onCut={onCut} />
    </LocaleGate>,
  );
  expect(screen.queryByLabelText('Show bubble')).toBeNull();
  expect(screen.getByRole('button', { name: 'Cutting…' })).toBeDisabled();
  expect(screen.getByText('Working')).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'Why this is needed: Notification' }));
  expect(screen.getByText(/quiet notification keeps bubble controls/i)).toBeTruthy();
  await view.unmount();
  await render(
    <LocaleGate>
      <HomeScreen home={{ ...home, bubbleSupported: false }} busy={false} notice="" onRefresh={() => undefined} onSettings={() => undefined} onCut={onCut} />
    </LocaleGate>,
  );
  expect(screen.queryByLabelText('Show bubble')).toBeNull();
  await userEvent.press(screen.getByRole('button', { name: 'Cut here' }));
  await userEvent.press(screen.getByRole('button', { name: 'Gallery' }));
  await dismissSourceModal();
  expect(onCut).toHaveBeenCalledWith('gallery');
  const os = jest.replaceProperty(Platform, 'OS', 'android');
  const android = await render(
    <LocaleGate>
      <HomeScreen home={home} busy={false} notice="" onRefresh={() => undefined} onSettings={() => undefined} onCut={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.getByText(text('en', 'bubbleOptional'))).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'OK' }));
  expect(screen.queryByText(text('en', 'bubbleOptional'))).toBeNull();
  await android.unmount();
  os.restore();
}, 15000);

async function setBubble(value: boolean) {
  const control = screen.getByLabelText('Show bubble');
  await act(async () => { control.props.onChange({ nativeEvent: { value } }); });
  await flush();
}

test('the bubble switch explains a missing overlay and notifications use the Android version-specific flow', async () => {
  const os = jest.replaceProperty(Platform, 'OS', 'android');
  const version = jest.spyOn(Platform, 'Version', 'get');
  version.mockReturnValue(33);
  PermissionsAndroid.request = jest.fn(async () => 'granted');
  PermissionsAndroid.PERMISSIONS = { ...PermissionsAndroid.PERMISSIONS, POST_NOTIFICATIONS: 'android.permission.POST_NOTIFICATIONS' };
  const blocked = {
    ...home,
    rows: [
      { kind: 'overlay' as const, title: 'Overlay', status: 'denied' as const, need: 'requiredForBubble' as const },
      { kind: 'notifications' as const, title: 'Notifications', status: 'denied' as const, need: 'optional' as const },
    ],
  };
  const onRefresh = jest.fn();
  const view = await render(
    <LocaleGate>
      <HomeScreen home={blocked} busy notice="Try again" onRefresh={onRefresh} onSettings={() => undefined} onCut={() => undefined} />
    </LocaleGate>,
  );
  await setBubble(true);
  expect(PermissionsAndroid.request).not.toHaveBeenCalled();
  expect(erase.setBubble).not.toHaveBeenCalled();
  await userEvent.press(screen.getByRole('button', { name: 'Open settings' }));
  expect(erase.openPermission).toHaveBeenCalledWith('overlay');
  await userEvent.press(screen.getByRole('button', { name: 'Notification, Off, Optional' }));
  expect(PermissionsAndroid.request).toHaveBeenCalledWith('android.permission.POST_NOTIFICATIONS');
  await view.unmount();
  version.mockReturnValue(32);
  const older = await render(
    <LocaleGate>
      <HomeScreen home={home} busy={false} notice="" onRefresh={onRefresh} onSettings={() => undefined} onCut={() => undefined} />
    </LocaleGate>,
  );
  await setBubble(true);
  expect(erase.openPermission).toHaveBeenCalledTimes(1);
  await userEvent.press(screen.getByRole('button', { name: 'Notification, Off, Optional' }));
  expect(erase.openPermission).toHaveBeenCalledWith('notifications');
  await older.unmount();
  const enabled = await render(
    <LocaleGate>
      <HomeScreen home={{ ...home, bubbleEnabled: true, clipboardReady: true }} busy={false} notice="" onRefresh={onRefresh} onSettings={() => undefined} onCut={jest.fn()} />
    </LocaleGate>,
  );
  await userEvent.press(screen.getByRole('button', { name: 'Cut here' }));
  await userEvent.press(screen.getByRole('button', { name: 'Clipboard' }));
  await dismissSourceModal();
  erase.setBubble.mockRejectedValueOnce(new Error('toggle failed'));
  await setBubble(false);
  expect(screen.getByText('Could not read the picture')).toBeTruthy();
  await setBubble(false);
  expect(erase.setBubble).toHaveBeenCalledWith(false);
  await userEvent.press(screen.getByRole('button', { name: 'Sample frame' }));
  version.mockRestore();
  os.restore();
});

test('overlay setup counts only a completed settings round-trip and enables Bubble after a grant', async () => {
  jest.replaceProperty(Platform, 'OS', 'android');
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    listeners.push(listener as (state: string) => void | Promise<void>);
    return { remove: () => undefined };
  });
  const onRefresh = jest.fn();
  const blocked = { ...home, rows: [{ kind: 'overlay' as const, title: 'Overlay', status: 'denied' as const, need: 'optional' as const }] };
  const view = await render(<LocaleGate><HomeScreen home={blocked} busy={false} notice="" onRefresh={onRefresh} onSettings={() => undefined} onCut={() => undefined} /></LocaleGate>);
  await setBubble(true);
  await userEvent.press(screen.getByRole('button', { name: 'Not now' }));
  expect(screen.queryByText('Turn on the bubble?')).toBeNull();
  erase.openPermission.mockRejectedValueOnce(new Error('settings unavailable'));
  await setBubble(true);
  await userEvent.press(screen.getByRole('button', { name: 'Open settings' })); await flush();
  expect(screen.getByText('Could not read the picture')).toBeTruthy();
  await setBubble(true);
  await userEvent.press(screen.getByRole('button', { name: 'Open settings' })); await flush();
  const returning = async (state: string) => {
    AppState.currentState = state as typeof AppState.currentState;
    await act(async () => { for (const listener of listeners) await listener(state); });
  };
  await returning('active');
  expect(erase.setBubble).not.toHaveBeenCalled();
  await returning('background');
  erase.getHome.mockResolvedValue({ ...blocked, rows: [{ ...blocked.rows[0], status: 'granted' }] });
  await returning('active'); await flush();
  expect(erase.setBubble).toHaveBeenCalledWith(true);
  await returning('active');
  expect(erase.setBubble).toHaveBeenCalledTimes(1);
  await view.unmount();
});

test('bubble setup reports failure if enabling it after granted permission fails', async () => {
  const os = jest.replaceProperty(Platform, 'OS', 'android');
  let stateHandler: ((state: string) => void | Promise<void>) | undefined;
  const appState = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    stateHandler = listener as (state: string) => void | Promise<void>;
    return { remove: () => undefined };
  });
  const onRefresh = jest.fn();
  erase.openPermission.mockResolvedValue(undefined);
  erase.getHome.mockResolvedValue({ ...home, rows: [{ kind: 'overlay', title: 'Overlay', status: 'granted', need: 'requiredForBubble' }] });
  erase.setBubble.mockRejectedValueOnce(new Error('bubble service unavailable'));
  const blocked = {
    ...home,
    rows: [{ kind: 'overlay' as const, title: 'Overlay', status: 'denied' as const, need: 'requiredForBubble' as const }],
  };
  await render(
    <LocaleGate>
      <HomeScreen home={blocked} busy={false} notice="" onRefresh={onRefresh} onSettings={() => undefined} onCut={() => undefined} />
    </LocaleGate>,
  );
  await setBubble(true);
  await userEvent.press(screen.getByRole('button', { name: 'Open settings' }));
  await flush();
  await act(async () => { await stateHandler?.('background');
    await stateHandler?.('active'); });
  expect(screen.getByText('Could not read the picture')).toBeTruthy();
  expect(erase.setBubble).toHaveBeenCalledWith(true);
  expect(onRefresh).toHaveBeenCalled();
  appState.mockRestore();
  os.restore();
}, 15000);

test('settings shows both pin tips and both sheets', async () => {
  const os = jest.replaceProperty(Platform, 'OS', 'android');
  const onLanguage = jest.fn();
  const onBack = jest.fn();
  const view = await render(
    <LocaleGate>
      <SettingsScreen showXiaomiTip onBack={onBack} onLanguage={onLanguage} />
    </LocaleGate>,
  );
  await userEvent.press(screen.getByRole('button', { name: 'Bubble disappears or won’t open' }));
  expect(screen.getByText(/Security/)).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'OK' }));
  await userEvent.press(screen.getByRole('button', { name: 'Language' }));
  await userEvent.press(screen.getByRole('button', { name: 'How it works' }));
  await userEvent.press(screen.getByRole('button', { name: 'OK' }));
  await userEvent.press(screen.getByRole('button', { name: 'Licenses' }));
  expect(screen.getByText(/ONNX Runtime/)).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'Back' }));
  expect(onLanguage).toHaveBeenCalled();
  expect(onBack).toHaveBeenCalled();
  await view.unmount();
  await render(
    <LocaleGate>
      <SettingsScreen showXiaomiTip={false} onBack={() => undefined} onLanguage={() => undefined} />
    </LocaleGate>,
  );
  await userEvent.press(screen.getByRole('button', { name: 'Bubble disappears or won’t open' }));
  expect(screen.getByText(/unrestricted/)).toBeTruthy();
  os.restore();
});

test('settings exposes theme, privacy, policy, and feedback controls', async () => {
  const onTheme = jest.fn();
  const onAnalytics = jest.fn();
  const onCrash = jest.fn();
  const onPrivacy = jest.fn();
  const onFeedback = jest.fn();
  await render(
    <LocaleGate>
      <SettingsScreen
        showXiaomiTip={false}
        onBack={() => undefined}
        onLanguage={() => undefined}
        themeMode="system"
        onThemeModeChange={onTheme}
        analyticsEnabled={false}
        crashEnabled
        onAnalyticsChange={onAnalytics}
        onCrashChange={onCrash}
        onOpenPrivacy={onPrivacy}
        onFeedback={onFeedback}
      />
    </LocaleGate>,
  );
  expect(screen.getByRole('button', { name: 'System' })).toBeSelected();
  await userEvent.press(screen.getByRole('button', { name: 'Dark' }));
  await userEvent.press(screen.getByRole('button', { name: 'Light' }));
  expect(onTheme.mock.calls).toEqual([['dark'], ['light']]);
  await userEvent.press(screen.getByRole('switch', { name: 'Share usage data' }));
  await userEvent.press(screen.getByRole('switch', { name: 'Share crash reports' }));
  expect(onAnalytics).toHaveBeenCalledWith(true);
  expect(onCrash).toHaveBeenCalledWith(false);
  await userEvent.press(screen.getByRole('button', { name: 'Privacy policy' }));
  await userEvent.press(screen.getByRole('button', { name: 'Send feedback' }));
  expect(onPrivacy).toHaveBeenCalledTimes(1);
  expect(onFeedback).toHaveBeenCalledTimes(1);
});

test('language list selects the system and a language', async () => {
  await render(
    <LocaleGate>
      <LanguageScreen onBack={() => undefined} />
    </LocaleGate>,
  );
  await act(async () => { await Promise.resolve(); });
  await userEvent.press(screen.getByRole('button', { name: /Match system/ }));
  await userEvent.press(screen.getByRole('button', { name: /Русский/ }));
  await emitDevice('eraseLocale', { selected: 'ru', system: 'en' });
  await act(async () => { await Promise.resolve(); });
  expect(erase.setLocale).toHaveBeenCalledWith('');
  expect(erase.setLocale).toHaveBeenCalledWith('ru');
});

test('result copy and save report success and failure', async () => {
  const onEdit = jest.fn();
  const onBack = jest.fn();
  await render(
    <LocaleGate>
      <ResultScreen session={readySession()} onBack={onBack} onEdit={onEdit} />
    </LocaleGate>,
  );
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Copied')).toBeTruthy();
  let releaseCopy: (value: string) => void = () => undefined;
  erase.publishPng.mockImplementationOnce(() => new Promise<string>(resolve => { releaseCopy = resolve; }));
  const copy = screen.getByRole('button', { name: 'Copy' });
  const publishCalls = erase.publishPng.mock.calls.length;
  await act(async () => {
    copy.props.onClick();
    copy.props.onClick();
    await Promise.resolve();
  });
  expect(erase.publishPng).toHaveBeenCalledTimes(publishCalls + 1);
  await act(async () => {
    releaseCopy('copied');
    for (let step = 0; step < 8; step += 1) await Promise.resolve();
  });
  erase.publishPng.mockResolvedValueOnce('empty');
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Nothing to copy')).toBeTruthy();
  erase.publishPng.mockRejectedValueOnce(new Error('clipboard unavailable'));
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  let release: (value: string) => void = () => undefined;
  erase.saveToGallery.mockImplementation(() => new Promise<string>(resolve => { release = resolve; }));
  const save = screen.getByRole('button', { name: 'Save' });
  await act(async () => {
    save.props.onClick();
    save.props.onClick();
    await Promise.resolve();
  });
  expect(erase.saveToGallery).toHaveBeenCalled();
  await act(async () => {
    release('saved');
    for (let step = 0; step < 8; step += 1) await Promise.resolve();
  });
  expect(screen.getByText('Saved to gallery')).toBeTruthy();
  erase.saveToGallery.mockReset();
  erase.saveToGallery.mockResolvedValue('failed');
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  erase.saveToGallery.mockRejectedValueOnce(new Error('gallery unavailable'));
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  await userEvent.press(screen.getByRole('button', { name: 'Edit in app' }));
  await userEvent.press(screen.getByRole('button', { name: 'Back' }));
  expect(onEdit).toHaveBeenCalled();
  expect(onBack).toHaveBeenCalled();
  await screen.unmount();
});

test('result without a file disables export, while SVG can be copied but not saved as PNG', async () => {
  await render(
    <LocaleGate>
      <ResultScreen session={{ status: 'failed', message: '' }} onBack={() => undefined} onEdit={() => undefined} />
      <ResultScreen session={readySession('svg')} onBack={() => undefined} onEdit={() => undefined} />
    </LocaleGate>,
  );
  const copyButtons = screen.getAllByRole('button', { name: 'Copy' });
  const saveButtons = screen.getAllByRole('button', { name: 'Save' });
  expect(copyButtons[0]).toBeDisabled();
  expect(saveButtons[0]).toBeDisabled();
  expect(copyButtons[1]).not.toBeDisabled();
  expect(saveButtons[1]).toBeDisabled();
  await userEvent.press(copyButtons[1]);
  await flush();
  expect(erase.publishPng).toHaveBeenCalledWith('/c.png');
});

test('processing announces the stage and shows progress animation only during inference', async () => {
  await render(
    <LocaleGate>
      <ProcessingScreen previewPath="/preview.jpg" percent={40} stage="inference" onCancel={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.getByText('Removing the background on this device…')).toBeTruthy();
  expect(screen.getByLabelText('Removing the background on this device…')).toBeTruthy();
  expect(screen.getByRole('progressbar')).toHaveAccessibilityValue({ min: 0, max: 100, now: 40 });
  expect(screen.getByText('40%')).toBeTruthy();
  await screen.unmount();
  await render(
    <LocaleGate>
      <ProcessingScreen previewPath="/preview.jpg" percent={92} stage="exporting" onCancel={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.getByText('Preparing your cutout…')).toBeTruthy();
  expect(screen.getByText('92%')).toBeTruthy();
  expect(screen.queryByLabelText('Preparing your cutout…')).toBeNull();
  await screen.unmount();
  await render(
    <LocaleGate>
      <ProcessingScreen previewPath={null} percent={0} onCancel={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.getByText('Cutting…')).toBeTruthy();
});

test('the bubble streams progress and answers save', async () => {
  erase.getBubbleState.mockRejectedValueOnce(new Error('off'));
  await render(<LocaleGate><BubbleApp /></LocaleGate>);
  await flush();
  await emitDevice('bubbleState', { ...bubble, expanded: false, anchorEnd: false });
  await emitDevice('eraseProgress', {});
  await emitDevice('erasePreview', {});
  await emitDevice('bubbleState', { ...bubble, phase: 'working', percent: 8, previewPath: '/src.jpg', anchorEnd: false });
  await emitDevice('eraseProgress', { percent: 42 });
  await emitDevice('erasePreview', { path: '/src.jpg' });
  await emitDevice('bubbleState', { ...bubble, phase: 'working', percent: 42, previewPath: '/src.jpg', anchorEnd: false });
  await flush();
  expect(screen.getByText('42%')).toBeTruthy();
  await emitDevice('eraseProgress', { percent: 10 });
  await flush();
  await screen.unmount();
  await render(<LocaleGate><BubbleApp /></LocaleGate>);
  await flush();
  await emitDevice('bubbleState', { ...bubble, phase: 'copied', editReady: false, previewPath: '/out.png' });
  await flush();
  await emitDevice('bubbleState', { ...bubble, clipboardReady: true });
  await flush();
  await act(async () => {
    screen.getByRole('button', { name: 'Clipboard' }).props.onClick();
    await Promise.resolve();
  });
  await userEvent.press(screen.getByRole('button', { name: 'Cut background' }));
  await userEvent.press(screen.getByRole('button', { name: 'Gallery' }));
  await dismissSourceModal();
  await userEvent.press(screen.getByRole('button', { name: 'File' }));
  await dismissSourceModal();
  await screen.unmount();
});

test('the bubble reports a saved cutout and a missing one', async () => {
  const toast = jest.spyOn(ToastAndroid, 'show');
  erase.bubbleAction.mockImplementation(async () => ({ ...bubble, phase: 'copied', previewPath: '/out.png', editReady: true }));
  erase.getBubbleState.mockImplementation(() => new Promise(() => undefined));
  await render(<LocaleGate><BubbleApp /></LocaleGate>);
  await flush();
  await emitDevice('erasePreview', { path: '/idle.jpg' });
  await emitDevice('bubbleState', { ...bubble, phase: 'copied', previewPath: '/out.png', editReady: true, message: 'copied' });
  await flush();
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  erase.publishPng.mockResolvedValueOnce('empty');
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  let releaseCopy: (value: string) => void = () => undefined;
  erase.publishPng.mockImplementationOnce(() => new Promise<string>(resolve => { releaseCopy = resolve; }));
  const copy = screen.getByRole('button', { name: 'Copy' });
  const publishCalls = erase.publishPng.mock.calls.length;
  await act(async () => {
    copy.props.onClick();
    copy.props.onClick();
    await Promise.resolve();
  });
  expect(erase.publishPng).toHaveBeenCalledTimes(publishCalls + 1);
  await act(async () => {
    releaseCopy('copied');
    for (let step = 0; step < 8; step += 1) await Promise.resolve();
  });
  erase.saveToGallery.mockResolvedValueOnce('saved');
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(toast).toHaveBeenCalledWith('Saved to gallery', ToastAndroid.SHORT);
  let release: (value: string) => void = () => undefined;
  erase.saveToGallery.mockImplementation(() => new Promise<string>(resolve => { release = resolve; }));
  const save = screen.getByRole('button', { name: 'Save' });
  await act(async () => {
    save.props.onClick();
    save.props.onClick();
    await Promise.resolve();
  });
  expect(erase.saveToGallery).toHaveBeenCalled();
  await act(async () => {
    release('nope');
    for (let step = 0; step < 8; step += 1) await Promise.resolve();
  });
  expect(screen.getByText('Could not save')).toBeTruthy();
  erase.bubbleAction.mockImplementation(() => new Promise(() => undefined));
  await act(async () => {
    (screen.getByRole('button', { name: 'Edit in app' }).props.onClick as () => void)();
    (screen.getByRole('button', { name: 'Cut another' }).props.onClick as () => void)();
    await Promise.resolve();
  });
  await emitDevice('bubbleState', { ...bubble, phase: 'failed', editReady: true, previewPath: null });
  await flush();
  expect(screen.getByRole('button', { name: 'Copy' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  await act(async () => {
    (screen.getByRole('button', { name: 'Hide bubble' }).props.onClick as () => void)();
    await Promise.resolve();
  });
  expect(erase.hideBubble).toHaveBeenCalled();
  erase.bubbleAction.mockReset();
  erase.bubbleAction.mockResolvedValue({ ...bubble, message: 'copied' });
  erase.getBubbleState.mockReset();
  erase.getBubbleState.mockResolvedValue({ ...bubble, expanded: false });
  erase.getBubbleGeometry.mockResolvedValue(null);
  erase.saveToGallery.mockReset();
  erase.saveToGallery.mockResolvedValue('saved');
  await screen.unmount();
});

test('a native bubble snapshot already queued during unmount is ignored', async () => {
  const subscription = jest.spyOn(DeviceEventEmitter, 'addListener');
  const view = await render(<LocaleGate><BubbleApp /></LocaleGate>);
  await flush();
  const listener = subscription.mock.calls.find(([event]) => event === 'bubbleState')?.[1] as ((snapshot: unknown) => void) | undefined;
  expect(listener).toBeDefined();
  await view.unmount();
  await act(async () => {
    listener?.({ ...bubble, expanded: true, revision: 20 });
    await Promise.resolve();
  });
});

test('the bubble preserves newer state and reports inference, reduced motion, and action errors', async () => {
  let resolveInitial: (snapshot: typeof bubble & { revision?: number }) => void = () => undefined;
  erase.getBubbleState.mockImplementation(() => new Promise(resolve => { resolveInitial = resolve; }));
  const motionListener = jest.spyOn(AccessibilityInfo, 'addEventListener');
  await render(<LocaleGate><BubbleApp /></LocaleGate>);
  const reducedMotionChange = motionListener.mock.calls.at(-1)![1] as (value: boolean) => void;

  await emitDevice('bubbleState', { ...bubble, phase: 'copied', expanded: true, editReady: true, previewPath: '/new.png', revision: 10 });
  expect(screen.getByRole('button', { name: 'Cut another' })).toBeTruthy();
  await emitDevice('bubbleState', { ...bubble, expanded: false, revision: 9 });
  expect(screen.getByRole('button', { name: 'Cut another' })).toBeTruthy();
  await act(async () => { resolveInitial({ ...bubble, expanded: false, revision: 1 }); });
  await flush();
  expect(screen.getByRole('button', { name: 'Cut another' })).toBeTruthy();

  erase.bubbleAction.mockResolvedValueOnce({ ...bubble, expanded: false, revision: 8, message: 'copied' });
  await userEvent.press(screen.getByRole('button', { name: 'Cut another' }));
  await flush();
  expect(screen.getByRole('button', { name: 'Cut another' })).toBeTruthy();
  expect(screen.getByText('Copied')).toBeTruthy();

  await emitDevice('bubbleState', { ...bubble, phase: 'working', stage: 'inference', percent: 12, expanded: true, previewPath: '/progress.png', revision: 11 });
  expect(screen.getByText('Removing the background on this device…')).toBeTruthy();
  expect(screen.getByText('12%')).toBeTruthy();
  expect(screen.getByRole('progressbar')).toHaveAccessibilityValue({ min: 0, max: 100, now: 12 });
  await act(async () => { reducedMotionChange(true); });
  await emitDevice('bubbleState', { ...bubble, phase: 'working', stage: 'exporting', percent: 78, expanded: true, previewPath: '/progress.png', revision: 12 });
  expect(screen.getByText('78%')).toBeTruthy();

  await emitDevice('bubbleState', { ...bubble, expanded: true, revision: 13 });
  erase.bubbleAction.mockRejectedValueOnce(new Error('native action failed'));
  await userEvent.press(screen.getByRole('button', { name: 'Gallery' }));
  await dismissSourceModal();
  await flush();
  expect(screen.getByText('Could not read the picture')).toBeTruthy();

  await emitDevice('bubbleState', { ...bubble, phase: 'copied', expanded: true, editReady: true, previewPath: '/out.png', revision: 14 });
  erase.publishPng.mockRejectedValueOnce(new Error('clipboard unavailable'));
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  erase.saveToGallery.mockRejectedValueOnce(new Error('gallery unavailable'));
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  await screen.unmount();
});

function hands() {
  const current = compositions.at(-1)?.gestures ?? [];
  return {
    draw: current.find(gesture => gesture.kind === 'pan' && gesture.pointerCount === 1)!,
    pinch: current.find(gesture => gesture.kind === 'pinch')!,
    drag: current.find(gesture => gesture.kind === 'pan' && gesture.pointerCount === 2)!,
  };
}

function layoutCanvas(root: { props?: { onLayout?: (event: unknown) => void }; children?: unknown[] }) {
  const visit = (node: { props?: { onLayout?: (event: unknown) => void }; children?: unknown[] } | string | null) => {
    if (!node || typeof node !== 'object') return;
    node.props?.onLayout?.({ nativeEvent: { layout: { width: 200, height: 200, x: 0, y: 0 } } });
    for (const child of node.children ?? []) visit(child as { props?: { onLayout?: (event: unknown) => void }; children?: unknown[] });
  };
  return act(async () => { visit(root); });
}

type UiTreeNode = { type: string; props: Record<string, unknown>; children?: Array<UiTreeNode | string | null> };

function flattenUiTree(node: unknown): UiTreeNode[] {
  if (node == null || typeof node !== 'object') return [];
  const item = node as UiTreeNode;
  return [item, ...(item.children ?? []).flatMap(child => flattenUiTree(child))];
}

test('the editor previews absolute cutout alpha and exports that same mask while editing', async () => {
  const cutoutAlpha = new Uint8Array(16).fill(255);
  cutoutAlpha[10] = 64;
  const sourceAlpha = new Uint8Array(16).fill(255);
  sourceAlpha[0] = 0;
  sourceAlpha[10] = 128;
  global.__imageAlpha.set(sourceAlpha);
  erase.readAlpha.mockResolvedValue(bytesToBase64(cutoutAlpha));
  const editor = await render(
    <LocaleGate>
      <EditorScreen originalPath="/alpha-original.png" cutoutPath="/alpha-cutout.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  if (editor.root) await layoutCanvas(editor.root);
  if (editor.root) await layoutCanvas(editor.root);
  await flush();
  expect(screen.getByText('Erase the extra or restore an edge')).toBeTruthy();
  await act(async () => {
    (screen.getByRole('button', { name: 'Undo' }).props.onClick as () => void)();
    (screen.getByRole('button', { name: 'Redo' }).props.onClick as () => void)();
    await Promise.resolve();
  });

  const initialCoverage = global.__maskImages.at(-1)?.pixels;
  expect(global.__skiaGroups.some(group => group.layer === true)).toBe(true);
  expect(screen.getAllByTestId('sk-image').map(image => [image.props.x, image.props.y, image.props.width, image.props.height, image.props.fit])).toEqual([
    [0, 0, 200, 200, 'fill'],
    [0, 0, 200, 200, 'fill'],
  ]);
  expect(initialCoverage?.[0]).toBe(0);
  expect(initialCoverage?.[10]).toBe(128);

  const draw = hands().draw;
  await act(async () => {
    draw.handlers.onBegin({ x: 125, y: 125 });
    draw.handlers.onUpdate({ x: 125, y: 125, numberOfPointers: 2 });
    draw.handlers.onFinalize({ numberOfPointers: 2 }, false);
    await Promise.resolve();
  });
  expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled();

  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  const copiedMask = base64ToBytes(erase.writeMasked.mock.calls.at(-1)[1] as string);
  expect(copiedMask[0]).toBe(255);
  expect(copiedMask[10]).toBe(64);
  expect(erase.publishPng).toHaveBeenCalledWith('/masked.png');
  erase.publishPng.mockResolvedValueOnce('empty');
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Nothing to copy')).toBeTruthy();

  await userEvent.press(screen.getByRole('button', { name: 'Erase' }));
  const compare = screen.getByRole('button', { name: 'Hold to compare' });
  await userEvent.press(compare);
  expect(screen.getAllByTestId('sk-image')).toHaveLength(2);
  await userEvent.press(screen.getByRole('button', { name: 'Erase 18' }));
  await userEvent.press(screen.getByRole('button', { name: 'Erase 64' }));

  const stroke = hands().draw;
  await act(async () => {
    stroke.handlers.onBegin({ x: 125, y: 125 });
    stroke.handlers.onUpdate({ x: 125, y: 125, numberOfPointers: 1 });
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    stroke.handlers.onFinalize({ numberOfPointers: 1 }, true);
    await Promise.resolve();
  });
  expect(screen.getByRole('button', { name: 'Undo' })).toBeEnabled();
  global.__makeImage.set(false);
  await userEvent.press(screen.getByRole('button', { name: 'Undo' }));
  expect(screen.getByRole('button', { name: 'Undo' })).toBeEnabled();
  global.__makeImage.set(true);
  await userEvent.press(screen.getByRole('button', { name: 'Undo' }));
  expect(screen.getByRole('button', { name: 'Redo' })).toBeEnabled();
  await userEvent.press(screen.getByRole('button', { name: 'Redo' }));
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  const editedMask = base64ToBytes(erase.writeMasked.mock.calls.at(-1)[1] as string);
  expect(editedMask[10]).toBe(0);

  await userEvent.press(screen.getByRole('button', { name: 'Restore' }));
  const restore = hands().draw;
  await act(async () => {
    restore.handlers.onBegin({ x: 125, y: 125 });
    restore.handlers.onFinalize({ numberOfPointers: 1 }, true);
    await Promise.resolve();
  });
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  const restoredMask = base64ToBytes(erase.writeMasked.mock.calls.at(-1)[1] as string);
  expect(restoredMask[10]).toBeGreaterThan(0);

  const beforePinch = shared[2].value;
  const pinch = hands().pinch;
  const pan = hands().drag;
  await act(async () => {
    pinch.handlers.onStart({ focalX: 100, focalY: 100 });
    pinch.handlers.onUpdate({ scale: 2, focalX: 110, focalY: 105 });
    const initialPanX = shared[3].value;
    const initialPanY = shared[4].value;
    pan.handlers.onStart({ translationX: 0, translationY: 0 });
    pan.handlers.onUpdate({ translationX: 12, translationY: -8 });
    expect(shared[3].value).toBe(initialPanX);
    expect(shared[4].value).toBe(initialPanY);
    pinch.handlers.onFinalize();
    pan.handlers.onStart({ translationX: 0, translationY: 0 });
    pan.handlers.onUpdate({ translationX: 12, translationY: -8 });
    expect(shared[3].value).toBe(initialPanX + 12);
    expect(shared[4].value).toBe(initialPanY - 8);
  });
  expect(beforePinch).toBe(1);
  expect(shared[2].value).toBe(2);
  await userEvent.press(screen.getByRole('button', { name: 'Fit to screen' }));
  expect(shared[2].value).toBe(1);
  expect(shared[3].value).toBe(0);
  expect(shared[4].value).toBe(0);

  const firstSave = screen.getByRole('button', { name: 'Save' });
  await act(async () => {
    (firstSave.props.onClick as () => void)();
    (firstSave.props.onClick as () => void)();
    for (let step = 0; step < 8; step += 1) await Promise.resolve();
  });
  expect(erase.saveToGallery).toHaveBeenCalledWith('/masked.png');
  expect(erase.saveToGallery).toHaveBeenCalledTimes(1);
  erase.saveToGallery.mockResolvedValueOnce('failed');
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  erase.saveToGallery.mockRejectedValueOnce(new Error('gallery unavailable'));
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(erase.saveToGallery).toHaveBeenCalledTimes(4);
  await editor.unmount();
});

test('the editor draws a flat fill when the checker shader is missing', async () => {
  let releaseAlpha: (value: string) => void = () => undefined;
  erase.readAlpha.mockImplementation(() => new Promise<string>(resolve => { releaseAlpha = resolve; }));
  const dropped = await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/c.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await dropped.unmount();
  await act(async () => { releaseAlpha(bytesToBase64(Uint8Array.from({ length: 16 }, () => 255))); });
  erase.readAlpha.mockResolvedValue(bytesToBase64(Uint8Array.from({ length: 16 }, () => 255)));
  global.__shader.block();
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/c.png" width={4} height={4} note="note" onBack={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.getByText('note')).toBeTruthy();
});

test('the editor reports invalid source and cutout data without attempting export', async () => {
  global.__imagePixels.set(null);
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/invalid-source.png" cutoutPath="/valid-cutout.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  expect(screen.getByText('Opening')).toBeTruthy();
  await screen.unmount();

  global.__imagePixels.set(undefined);
  erase.readAlpha.mockRejectedValueOnce(new Error('mask read failed'));
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/missing-mask.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  expect(erase.writeMasked).not.toHaveBeenCalled();
  await screen.unmount();

  erase.readAlpha.mockResolvedValueOnce(bytesToBase64(Uint8Array.of(255, 255)));
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/wrong-size-mask.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  await screen.unmount();

  erase.readAlpha.mockResolvedValueOnce(bytesToBase64(new Uint8Array(0)));
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/zero-width.png" width={0} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  await screen.unmount();

  global.__makeImage.set(false);
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/unrenderable-mask.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  await screen.unmount();
});

test('the editor waits for its source image and reports a failed mask image creation', async () => {
  global.__imageAvailability.set(false);
  const renderEditor = () => (
    <LocaleGate>
      <EditorScreen originalPath="/loading.png" cutoutPath="/mask.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>
  );
  const editor = await render(renderEditor());
  await flush();
  expect(screen.getByText('Opening')).toBeTruthy();
  const compareWhileLoading = screen.getByRole('button', { name: 'Hold to compare' });
  await userEvent.longPress(compareWhileLoading, { duration: 120 });
  expect(screen.queryByTestId('sk-image')).toBeNull();
  const notReady = hands().draw;
  await act(async () => {
    notReady.handlers.onBegin({ x: 20, y: 20 });
    notReady.handlers.onUpdate({ x: 24, y: 24, numberOfPointers: 1 });
    notReady.handlers.onFinalize({ numberOfPointers: 1 }, true);
    notReady.handlers.onFinalize({ numberOfPointers: 2 }, false);
    await Promise.resolve();
  });

  global.__imageAvailability.set(true);
  await editor.rerender(renderEditor());
  await flush();
  expect(screen.getByText('Erase the extra or restore an edge')).toBeTruthy();

  global.__makeImage.set(false);
  await editor.rerender(
    <LocaleGate>
      <EditorScreen originalPath="/reloaded.png" cutoutPath="/mask.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  const draw = hands().draw;
  await act(async () => {
    draw.handlers.onBegin({ x: 0, y: 0 });
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    draw.handlers.onFinalize({ numberOfPointers: 1 }, true);
    await Promise.resolve();
  });
  expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled();
  await editor.unmount();
});

test('the editor ignores an alpha-read rejection after the screen unmounts', async () => {
  let rejectAlpha: (error: Error) => void = () => undefined;
  erase.readAlpha.mockImplementation(() => new Promise<string>((_resolve, reject) => { rejectAlpha = reject; }));
  const editor = await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/slow-mask.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  const pendingPinch = hands().pinch;
  await act(async () => {
    pendingPinch.handlers.onStart({ focalX: 10, focalY: 10 });
    await Promise.resolve();
  });
  await editor.unmount();
  await act(async () => {
    rejectAlpha(new Error('late alpha-read failure'));
    await Promise.resolve();
  });
});

test('copy and save report empty masks and release the save lock after failed writes', async () => {
  erase.readAlpha.mockResolvedValue(bytesToBase64(new Uint8Array(16)));
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/transparent.png" cutoutPath="/empty-cutout.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Nothing to copy')).toBeTruthy();
  expect(erase.writeMasked).not.toHaveBeenCalled();

  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  await screen.unmount();

  erase.readAlpha.mockResolvedValue(bytesToBase64(new Uint8Array(16).fill(255)));
  erase.writeMasked.mockResolvedValueOnce({ status: 'empty' });
  await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/c.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  await flush();
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Nothing to copy')).toBeTruthy();
  erase.writeMasked.mockRejectedValueOnce(new Error('write failed'));
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(screen.getByText('Could not save')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  await userEvent.press(screen.getByRole('button', { name: 'Save' }));
  await flush();
  expect(erase.saveToGallery).toHaveBeenCalledWith('/masked.png');
  erase.publishPng.mockRejectedValueOnce(new Error('clipboard failed'));
  await userEvent.press(screen.getByRole('button', { name: 'Copy' }));
  await flush();
  expect(screen.getByText('Nothing to copy')).toBeTruthy();
  await screen.unmount();
});

test('the editor lays the tools beside the picture on a wide window', async () => {
  const dimensions = jest.spyOn(Dimensions, 'get').mockReturnValue({ width: 900, height: 1200, scale: 1, fontScale: 1 });
  const editor = await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/c.png" width={4} height={4} note="wide" onBack={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.getByText('wide')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Restore' })).toBeOnTheScreen();
  expect(screen.getByRole('button', { name: 'Hold to compare' }).props.style).toEqual(expect.objectContaining({ minHeight: 48 }));
  expect(flattenUiTree(editor.toJSON()).filter(node => node.props.className === 'gap-2 pt-3')).toHaveLength(1);
  dimensions.mockRestore();
});

test('large text stacks editor tools and keeps actions comfortably tall', async () => {
  const dimensions = jest.spyOn(Dimensions, 'get').mockReturnValue({ width: 390, height: 800, scale: 1, fontScale: 1.3 });
  const editor = await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/c.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  expect(flattenUiTree(editor.toJSON()).filter(node => node.props.className === 'gap-2 pt-3')).toHaveLength(1);
  expect(screen.getByRole('button', { name: 'Hold to compare' }).props.style).toEqual(expect.objectContaining({ minHeight: 48 }));
  expect(screen.getByRole('button', { name: 'Fit to screen' }).props.style).toEqual(expect.objectContaining({ minHeight: 48 }));
  expect(screen.getByRole('button', { name: 'Restore' })).toBeEnabled();
  dimensions.mockRestore();
});

test('default-size editor keeps paired tool controls beside the image', async () => {
  const dimensions = jest.spyOn(Dimensions, 'get').mockReturnValue({ width: 390, height: 800, scale: 1, fontScale: 1 });
  const editor = await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/c.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  const nodes = flattenUiTree(editor.toJSON());
  expect(nodes.filter(node => node.props.className === 'gap-2 pt-4')).toHaveLength(1);
  expect(nodes.filter(node => node.props.className === 'flex-row gap-2')).toHaveLength(3);
  const eraseButton = screen.getByRole('button', { name: 'Erase' });
  const restoreButton = screen.getByRole('button', { name: 'Restore' });
  const eraseActiveColor = eraseButton.props.style.backgroundColor;
  const restoreIdleColor = restoreButton.props.style.backgroundColor;
  expect(eraseActiveColor).not.toBe(restoreIdleColor);
  await userEvent.press(restoreButton);
  expect(screen.getByRole('button', { name: 'Erase' }).props.style.backgroundColor).toBe(restoreIdleColor);
  expect(screen.getByRole('button', { name: 'Restore' }).props.style.backgroundColor).toBe(eraseActiveColor);
  await userEvent.press(screen.getByRole('button', { name: 'Erase' }));
  dimensions.mockRestore();
});

test('landscape expanded editor retains a scrollable side toolbar beside the canvas', async () => {
  const dimensions = jest.spyOn(Dimensions, 'get').mockReturnValue({ width: 1200, height: 800, scale: 1, fontScale: 1 });
  const editor = await render(
    <LocaleGate>
      <EditorScreen originalPath="/o.png" cutoutPath="/c.png" width={4} height={4} onBack={() => undefined} />
    </LocaleGate>,
  );
  const nodes = flattenUiTree(editor.toJSON());
  expect(nodes.filter(node => node.props.className === 'min-h-0 flex-1 flex-row')).toHaveLength(1);
  expect(nodes.filter(node => node.type === 'RCTScrollView' && (node.props.style as { width?: number } | undefined)?.width === 288)).toHaveLength(1);
  expect(screen.getByRole('button', { name: 'Erase' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Fit to screen' })).toBeEnabled();
  dimensions.mockRestore();
});

test('locale follows the system, an explicit tag, and restarts for direction', async () => {
  let release: (value: { selected: string; system: string }) => void = () => undefined;
  erase.getLocale.mockImplementation(() => new Promise(resolve => { release = resolve; }));
  const first = await render(<LocaleGate><Text>Child</Text></LocaleGate>);
  await first.unmount();
  await act(async () => { release({ selected: '', system: 'en' }); });
  erase.getLocale.mockRejectedValueOnce(new Error('no locale'));
  await render(<LocaleGate><LanguageScreen onBack={() => undefined} /></LocaleGate>);
  I18nManager.isRTL = false;
  await emitDevice('eraseLocale', { selected: '', system: 'ar' });
  await act(async () => { await Promise.resolve(); });
  expect(erase.restart).toHaveBeenCalled();
  I18nManager.isRTL = true;
  await emitDevice('eraseLocale', { system: 'en' });
  await act(async () => { await Promise.resolve(); });
  I18nManager.isRTL = false;
  await emitDevice('eraseLocale', { selected: 'en', system: 'en' });
  await emitDevice('eraseLocale', {});
  await act(async () => { await Promise.resolve(); });
});

test('useLocale works before the gate is mounted', async () => {
  function Probe() {
    const { t } = useLocale();
    return <Text>{t('save')}</Text>;
  }
  await render(<Probe />);
  expect(screen.getByText('Save')).toBeTruthy();
});

test('the sample frame is absent outside development', async () => {
  const dev = global.__DEV__;
  global.__DEV__ = false;
  const view = await render(
    <LocaleGate>
      <HomeScreen home={home} busy={false} notice="" onRefresh={() => undefined} onSettings={() => undefined} onCut={() => undefined} />
    </LocaleGate>,
  );
  expect(screen.queryByText('Sample frame')).toBeNull();
  await view.unmount();
  global.__DEV__ = dev;
});

test('screen duration is not recorded before AppState has become active', async () => {
  jest.useFakeTimers();
  const previousState = AppState.currentState;
  AppState.currentState = null;
  erase.logProductEvent = jest.fn(async () => undefined);
  await render(<App />);
  await act(async () => { jest.advanceTimersByTime(1500); });
  await userEvent.press(screen.getByRole('button', { name: 'Settings' }));
  expect(erase.logProductEvent).not.toHaveBeenCalledWith('screen_duration', expect.anything());
  delete erase.logProductEvent;
  AppState.currentState = previousState;
});

test('the app boots, cuts, and keeps the bubble switch in step', async () => {
  jest.useFakeTimers();
  const os = jest.replaceProperty(Platform, 'OS', 'android');
  const user = userEvent.setup();
  const openUrl = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
  const backs: Array<() => boolean> = [];
  const states: Array<(state: string) => void> = [];
  const backHandler = jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event, handler) => {
    backs.push(handler as () => boolean);
    return { remove: () => undefined };
  });
  const appState = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, handler) => {
    states.push(handler as (state: string) => void);
    return { remove: () => undefined };
  });
  erase.consumePendingEdit.mockResolvedValue(readySession('manual'));
  const view = await render(<App />);
  await act(async () => { await Promise.resolve(); });
  await act(async () => { jest.advanceTimersByTime(1500); });
  await act(async () => { await Promise.resolve(); });
  expect(openUrl).not.toHaveBeenCalled();
  expect(screen.getByText('Erase the extra or restore an edge')).toBeTruthy();
  await emitDevice('eraseOpenEdit', {});
  await act(async () => { await Promise.resolve(); });
  await emitDevice('bubbleEnabled', { enabled: true });
  await emitDevice('bubbleEnabled', {});
  await emitDevice('eraseProgress', { percent: 12 });
  await emitDevice('eraseProgress', {});
  await emitDevice('erasePreview', {});
  await emitDevice('erasePreview', { path: '/live.jpg' });
  erase.getHome.mockClear();
  await act(async () => { states[0]?.('background'); await Promise.resolve(); });
  expect(erase.getHome).not.toHaveBeenCalled();
  await act(async () => { states[0]?.('active'); await Promise.resolve(); });
  expect(erase.getHome).toHaveBeenCalled();
  await act(async () => { states.at(-1)?.('background'); });
  await act(async () => { states.at(-1)?.('active'); });
  await act(async () => { states.at(-1)?.('active'); });
  await act(async () => { states.at(-1)?.('background'); });
  await act(async () => { states.at(-1)?.('active'); });
  await user.press(screen.getByRole('button', { name: 'Back' }));
  await act(async () => { expect(backs.at(-1)?.()).toBe(false); });
  await view.unmount();

  states.length = 0;
  erase.consumePendingEdit.mockResolvedValue(null);
  erase.getHome.mockResolvedValue({ ...home, clipboardReady: true });
  erase.getPreferences = jest.fn(async () => ({ theme: 'dark', analytics: true, crashes: true }));
  erase.setPreferences = jest.fn(async () => undefined);
  await render(<App />);
  await act(async () => { await Promise.resolve(); });
  await act(async () => { jest.advanceTimersByTime(1500); });
  await act(async () => { await Promise.resolve(); });
  expect(screen.getByText('Cutout')).toBeTruthy();
  expect(erase.prepareModel).toHaveBeenCalled();
  erase.getHome.mockClear();
  await act(async () => { states[0]?.('active'); await Promise.resolve(); });
  expect(erase.getHome).toHaveBeenCalled();
  await setBubble(true);
  await user.press(screen.getByRole('button', { name: 'Settings' }));
  expect(screen.getByRole('button', { name: 'Dark' })).toBeSelected();
  await user.press(screen.getByRole('button', { name: 'System' }));
  await flush();
  expect(erase.setPreferences).toHaveBeenLastCalledWith('system', true, true);
  await user.press(screen.getByRole('switch', { name: 'Share usage data' }));
  await user.press(screen.getByRole('switch', { name: 'Share crash reports' }));
  await flush();
  expect(erase.setPreferences).toHaveBeenLastCalledWith('system', false, false);
  erase.setPreferences.mockRejectedValueOnce(new Error('preferences unavailable'));
  await user.press(screen.getByRole('button', { name: 'Dark' }));
  await flush();
  expect(screen.getByRole('button', { name: 'System' })).toBeSelected();
  expect(erase.setPreferences).toHaveBeenLastCalledWith('dark', false, false);
  expect(screen.getByText('Could not save')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Dark' }));
  await flush();
  expect(screen.getByRole('button', { name: 'Dark' })).toBeSelected();
  erase.setPreferences.mockRejectedValueOnce(new Error('analytics preference unavailable'));
  await user.press(screen.getByRole('switch', { name: 'Share usage data' }));
  await flush();
  expect(erase.setPreferences).toHaveBeenLastCalledWith('dark', true, false);
  expect(screen.getByText('Could not save')).toBeTruthy();
  erase.setPreferences.mockRejectedValueOnce(new Error('crash preference unavailable'));
  await user.press(screen.getByRole('switch', { name: 'Share crash reports' }));
  await flush();
  expect(erase.setPreferences).toHaveBeenLastCalledWith('dark', false, true);
  expect(screen.getByText('Could not save')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Privacy policy' }));
  expect(openUrl).toHaveBeenCalledWith('https://kigya.github.io/cutout/privacy/');
  expect(screen.queryByText(/images and clipboard content are processed on your device/i)).toBeNull();
  openUrl.mockRejectedValueOnce(new Error('policy cannot open'));
  await user.press(screen.getByRole('button', { name: 'Privacy policy' }));
  await flush();
  expect(screen.getByText('Could not open the privacy policy')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Send feedback' }));
  expect(screen.getByLabelText('Reply email')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Back' }));
  await user.press(screen.getByRole('button', { name: 'Send feedback' }));
  await act(async () => { backs.at(-1)?.(); });
  openUrl.mockRestore();
  await user.press(screen.getByRole('button', { name: 'Language' }));
  await user.press(screen.getByRole('button', { name: 'Back' }));
  await user.press(screen.getByRole('button', { name: 'Language' }));
  await act(async () => { backs.at(-1)?.(); });
  await user.press(screen.getByRole('button', { name: 'Back' }));
  await user.press(screen.getByRole('button', { name: 'Sample frame' }));
  await act(async () => { await Promise.resolve(); });
  expect(screen.getByText('Done')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Back' }));
  erase.cutFixture.mockResolvedValueOnce({ message: 'ready', originalPath: '/o.png', cutoutPath: '/c.png', width: 4, height: 4 });
  await user.press(screen.getByRole('button', { name: 'Sample frame' }));
  await flush();
  expect(screen.getByRole('button', { name: 'Copy' })).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Back' }));

  let releaseCut: (value: ReturnType<typeof readySession>) => void = () => undefined;
  erase.cutFixture.mockImplementation(() => new Promise(resolve => { releaseCut = resolve; }));
  await user.press(screen.getByRole('button', { name: 'Sample frame' }));
  await emitDevice('eraseProgress', { percent: 12 });
  await emitDevice('eraseProgress', { percent: 55 });
  await emitDevice('eraseProgress', { percent: 95 });
  await emitDevice('erasePreview', { path: '/live.jpg' });
  await act(async () => { backs.at(-1)?.(); });
  await act(async () => { releaseCut(readySession()); });

  let rejectCancelled: (error: Error) => void = () => undefined;
  erase.cutFixture.mockImplementation(() => new Promise((_resolve, reject) => { rejectCancelled = reject; }));
  await user.press(screen.getByRole('button', { name: 'Sample frame' }));
  await act(async () => { backs.at(-1)?.(); });
  await act(async () => {
    rejectCancelled(new Error('late cancellation failure'));
    for (let step = 0; step < 8; step += 1) await Promise.resolve();
  });
  expect(screen.getByRole('button', { name: 'Sample frame' })).toBeTruthy();
  expect(screen.queryByText('Could not read the picture')).toBeNull();

  erase.cutFixture.mockResolvedValueOnce({ status: 'failed', message: 'failed' });
  await user.press(screen.getByRole('button', { name: 'Sample frame' }));
  await act(async () => { await Promise.resolve(); });
  erase.cutFixture.mockResolvedValueOnce({ message: 'cancelled' });
  await user.press(screen.getByRole('button', { name: 'Sample frame' }));
  await act(async () => { await Promise.resolve(); });
  erase.cutFixture.mockRejectedValueOnce(new Error('cut failed'));
  await user.press(screen.getByRole('button', { name: 'Sample frame' }));
  await flush();
  expect(screen.getByText('Could not read the picture')).toBeTruthy();
  erase.cutClipboard.mockResolvedValueOnce(readySession('manual'));
  await user.press(screen.getByRole('button', { name: 'Cut here' }));
  await user.press(screen.getByRole('button', { name: 'Clipboard' }));
  await dismissSourceModal();
  await act(async () => { await Promise.resolve(); });
  expect(screen.getByText('Erase the extra or restore an edge')).toBeTruthy();
  await user.press(screen.getByRole('button', { name: 'Back' }));
  erase.pickAndCut.mockResolvedValueOnce(readySession());
  await user.press(screen.getByRole('button', { name: 'Cut here' }));
  await user.press(screen.getByRole('button', { name: 'File' }));
  await dismissSourceModal();
  await act(async () => { await Promise.resolve(); });
  expect(screen.getByText('Done')).toBeTruthy();
  await act(async () => { backs.at(-1)?.(); });
  erase.pickAndCut.mockResolvedValueOnce(readySession());
  await user.press(screen.getByRole('button', { name: 'Cut here' }));
  await user.press(screen.getByRole('button', { name: 'Gallery' }));
  await dismissSourceModal();
  await act(async () => { await Promise.resolve(); });
  await user.press(screen.getByRole('button', { name: 'Edit in app' }));
  await act(async () => { backs.at(-1)?.(); });
  delete erase.getPreferences;
  delete erase.setPreferences;
  backHandler.mockRestore();
  appState.mockRestore();
  os.restore();
}, 30000);


test('iOS waits for source modal dismissal before presenting a native picker and consumes it once', async () => {
  jest.replaceProperty(Platform, 'OS', 'ios');
  const onCut = jest.fn();
  await render(<LocaleGate><HomeScreen home={home} busy={false} notice="" onRefresh={() => undefined} onSettings={() => undefined} onCut={onCut} /></LocaleGate>);
  await userEvent.press(screen.getByRole('button', { name: 'Cut here' }));
  await userEvent.press(screen.getByRole('button', { name: 'Gallery' }));
  expect(onCut).not.toHaveBeenCalled();
  await dismissSourceModal();
  await dismissSourceModal();
  expect(onCut).toHaveBeenCalledTimes(1);
  expect(onCut).toHaveBeenCalledWith('gallery');
  await userEvent.press(screen.getByRole('button', { name: 'Cut here' }));
  await userEvent.press(screen.getByRole('button', { name: 'Back' }));
  await dismissSourceModal();
  expect(onCut).toHaveBeenCalledTimes(1);
});
