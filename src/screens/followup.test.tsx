import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useState } from 'react';
import { AppState, Keyboard, PermissionsAndroid, Platform, Pressable, Text, TextInput } from 'react-native';
import { createFocusVisibilityController, Screen } from '../components/ui/screen';
import { Sheet } from '../components/ui/sheet';
import { LocaleGate } from '../i18n/LocaleGate';
import { FeedbackScreen } from './FeedbackScreen';
import { HomeScreen } from './HomeScreen';
import { SettingsScreen } from './SettingsScreen';
import { ThemeProvider } from '../theme/ThemeProvider';
import type { HomeSnapshot } from '../native/Erase';

declare const global: { __erase: Record<string, jest.Mock> };
const native = global.__erase;
const originalPlatform = Platform.OS;

const home = (deniedCount = 0): HomeSnapshot => ({
  rows: [{ kind: 'overlay' as const, title: 'Over other apps', status: 'denied' as const, need: 'optional' as const, deniedCount }],
  bubbleEnabled: false,
  showXiaomiTip: false,
  clipboardReady: false,
  bubbleSupported: true,
});

const homeWithNotifications = (deniedCount = 0) => ({
  ...home(deniedCount),
  rows: [
    ...home(deniedCount).rows,
    { kind: 'notifications' as const, title: 'Notifications', status: 'denied' as const, need: 'optional' as const },
  ],
});

function renderHome(snapshot = home(), onRefresh = jest.fn()) {
  return render(
    <LocaleGate>
      <ThemeProvider mode="light">
        <HomeScreen
          home={snapshot}
          busy={false}
          notice=""
          diagnosticsNoticePending
          onDismissDiagnosticsNotice={jest.fn()}
          onPrivacy={jest.fn()}
          onRefresh={onRefresh}
          onSettings={jest.fn()}
          onCut={jest.fn()}
        />
      </ThemeProvider>
    </LocaleGate>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  Platform.OS = 'android';
  jest.spyOn(Platform, 'Version', 'get').mockReturnValue(35);
  AppState.currentState = 'active';
  native.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  native.beginPermissionRequest = jest.fn(async () => 'attempt-1');
  native.completePermissionRequest = jest.fn(async () => undefined);
  PermissionsAndroid.PERMISSIONS = {
    ...PermissionsAndroid.PERMISSIONS,
    POST_NOTIFICATIONS: 'android.permission.POST_NOTIFICATIONS',
  };
});

afterEach(() => {
  Platform.OS = originalPlatform;
  jest.restoreAllMocks();
});

test('Home discloses default diagnostics and keeps the overlay permission optional with Bubble off', async () => {
  const onDismissDiagnosticsNotice = jest.fn();
  await render(
    <LocaleGate>
      <ThemeProvider mode="light">
        <HomeScreen
          home={home()}
          busy={false}
          notice=""
          diagnosticsNoticePending
          onDismissDiagnosticsNotice={onDismissDiagnosticsNotice}
          onPrivacy={jest.fn()}
          onRefresh={jest.fn()}
          onSettings={jest.fn()}
          onCut={jest.fn()}
        />
      </ThemeProvider>
    </LocaleGate>,
  );

  expect(screen.getByText('Diagnostics are enabled by default')).toBeOnTheScreen();
  expect(screen.getByText(/Firebase receives technical usage events/)).toBeOnTheScreen();
  expect(screen.getByText('Optional')).toBeOnTheScreen();
  expect(screen.getByText('Cut out images from any app')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Dismiss' }));
  expect(onDismissDiagnosticsNotice).toHaveBeenCalledTimes(1);
});

test('Home permission rows keep stable handlers across unrelated parent state changes', async () => {
  const snapshot = home();
  const refresh = jest.fn();
  function Harness() {
    const [busy, setBusy] = useState(false);
    const [currentHome, setCurrentHome] = useState(snapshot);
    return (
      <>
        <Pressable accessibilityRole="button" accessibilityLabel="Toggle busy state" onPress={() => setBusy(value => !value)} />
        <Pressable accessibilityRole="button" accessibilityLabel="Refresh permission snapshot" onPress={() => setCurrentHome({ ...snapshot, clipboardReady: true, rows: [{ ...snapshot.rows[0], deniedCount: 2 }] })} />
        <HomeScreen home={currentHome} busy={busy} notice="" onRefresh={refresh} onSettings={jest.fn()} onCut={jest.fn()} />
      </>
    );
  }
  await render(<LocaleGate><ThemeProvider mode="light"><Harness /></ThemeProvider></LocaleGate>);
  const before = screen.getByRole('button', { name: 'Over other apps, Off, Optional' }).props.onPress;
  await fireEvent.press(screen.getByRole('button', { name: 'Toggle busy state' }));
  const after = screen.getByRole('button', { name: 'Over other apps, Off, Optional' }).props.onPress;
  expect(after).toBe(before);
  await fireEvent.press(screen.getByRole('button', { name: 'Refresh permission snapshot' }));
  const afterSnapshotRefresh = screen.getByRole('button', { name: 'Over other apps, Off, Optional' }).props.onPress;
  expect(afterSnapshotRefresh).toBe(before);
  await fireEvent.press(screen.getByRole('button', { name: 'Over other apps, Off, Optional' }));
  expect(native.openPermission).not.toHaveBeenCalled();
  expect(screen.getByText('Permission still off')).toBeOnTheScreen();
});

test('stale row and info callbacks do nothing after the latest snapshot removes the permission', async () => {
  const snapshot = home();
  await renderHome(snapshot);
  const staleOpen = screen.getByRole('button', { name: 'Over other apps, Off, Optional' });
  const staleInfo = screen.getByRole('button', { name: 'Why this is needed: Over other apps' });
  snapshot.rows.splice(0);

  await fireEvent.press(staleOpen);
  await fireEvent.press(staleInfo);
  expect(native.openPermission).not.toHaveBeenCalled();
  expect(screen.queryByText('Over other apps lets Cutout')).toBeNull();
});

test.each([
  { label: 'resolves', reject: false },
  { label: 'rejects', reject: true },
])('Bubble promotion handles an already-granted overlay when native activation $label', async ({ reject }) => {
  const granted = { ...home(), rows: [{ ...home().rows[0], status: 'granted' as const }] };
  native.setBubble.mockImplementationOnce(reject ? async () => { throw new Error('service unavailable'); } : async () => undefined);
  const refresh = jest.fn();
  await renderHome(granted, refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Enable bubble' }));
  if (reject) {
    expect(await screen.findByText('Could not read the picture')).toBeOnTheScreen();
  } else {
    expect(native.setBubble).toHaveBeenCalledWith(true);
    expect(refresh).toHaveBeenCalled();
  }
});

test.each([
  { scenario: 'already enabled', enabled: true, openPromo: false },
  { scenario: 'being enabled', enabled: false, openPromo: true },
])('overlay explanation keeps Bubble context when $scenario', async ({ enabled, openPromo }) => {
  const snapshot = { ...home(), bubbleEnabled: enabled };
  await renderHome(snapshot);

  if (openPromo) await fireEvent.press(screen.getByRole('button', { name: 'Enable bubble' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Why this is needed: Over other apps' }));

  expect(screen.getByText('Only the bubble needs this, so it can sit on top of other windows. Cutting inside the app works without it.')).toBeOnTheScreen();
});

test('Bubble setup proceeds to Settings and does not show a blocked rationale after one denial', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, callback: (state: string) => void) => {
    listeners.push(callback);
    return { remove: jest.fn() };
  }) as never);
  native.getHome.mockResolvedValue({ ...home(1), rows: [{ ...home(1).rows[0], deniedCount: 1 }] });
  const refresh = jest.fn();
  await renderHome(home(), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Enable bubble' }));
  expect(screen.getByText('Turn on the bubble?')).toBeOnTheScreen();
  const openSettingsButtons = screen.getAllByRole('button', { name: 'Open settings' });
  await fireEvent.press(openSettingsButtons[openSettingsButtons.length - 1]);
  expect(native.openPermission).toHaveBeenCalledWith('overlay');
  await act(async () => {
    AppState.currentState = 'background';
    for (const callback of listeners) await callback('background');
    AppState.currentState = 'active';
    for (const callback of listeners) await callback('active');
  });

  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
  expect(screen.queryByText('Permission still off')).toBeNull();
  expect(native.setBubble).not.toHaveBeenCalled();
  expect(refresh).toHaveBeenCalled();
});

test('Bubble setup enables the overlay after returning with a granted permission', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, callback: (state: string) => void) => {
    listeners.push(callback);
    return { remove: jest.fn() };
  }) as never);
  native.getHome.mockResolvedValue({ ...home(), rows: [{ ...home().rows[0], status: 'granted' }] });
  await renderHome(home());

  await fireEvent.press(screen.getByRole('button', { name: 'Enable bubble' }));
  const openSettingsButtons = screen.getAllByRole('button', { name: 'Open settings' });
  await fireEvent.press(openSettingsButtons[openSettingsButtons.length - 1]);
  await act(async () => {
    AppState.currentState = 'background';
    for (const callback of listeners) await callback('background');
    AppState.currentState = 'active';
    for (const callback of listeners) await callback('active');
  });

  expect(native.completePermissionRequest).toHaveBeenCalledWith('overlay', 'attempt-1', false);
  expect(native.setBubble).toHaveBeenCalledWith(true);
});

test('a duplicate permission-row tap while native Settings is still launching is ignored', async () => {
  let resolveLaunch: (() => void) | undefined;
  native.openPermission.mockImplementationOnce(() => new Promise<void>(resolve => { resolveLaunch = () => resolve(); }));
  await renderHome(home());

  await fireEvent.press(screen.getByRole('button', { name: 'Over other apps, Off, Optional' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Over other apps, Off, Optional' }));
  expect(native.openPermission).toHaveBeenCalledTimes(1);
  await act(async () => { resolveLaunch?.(); await Promise.resolve(); });
  expect(native.beginPermissionRequest).toHaveBeenCalledTimes(1);
});

test('Bubble setup launch failure cancels the pending enable and reports the failed settings handoff', async () => {
  native.openPermission.mockRejectedValueOnce(new Error('settings unavailable'));
  const refresh = jest.fn();
  await renderHome(home(), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Enable bubble' }));
  const openSettingsButtons = screen.getAllByRole('button', { name: 'Open settings' });
  await fireEvent.press(openSettingsButtons[openSettingsButtons.length - 1]);

  expect(await screen.findByRole('alert')).toHaveTextContent('Could not read the picture');
  expect(native.beginPermissionRequest).not.toHaveBeenCalled();
  expect(native.setBubble).not.toHaveBeenCalled();
  expect(screen.getByText('Enable the bubble to start a cutout from other apps. In-app cutting remains available.')).toBeOnTheScreen();
  expect(refresh).toHaveBeenCalled();
});

test('a failed begin-history read and snapshot never invent a blocked permission state', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, callback: (state: string) => void) => {
    listeners.push(callback);
    return { remove: jest.fn() };
  }) as never);
  native.beginPermissionRequest.mockRejectedValueOnce(new Error('history store unavailable'));
  native.getHome.mockRejectedValueOnce(new Error('snapshot unavailable'));
  const refresh = jest.fn();
  await renderHome(home(), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Over other apps, Off, Optional' }));
  await waitFor(() => expect(native.beginPermissionRequest).toHaveBeenCalledTimes(1));
  await act(async () => {
    AppState.currentState = 'background';
    for (const callback of listeners) await callback('background');
    AppState.currentState = 'active';
    for (const callback of listeners) await callback('active');
  });

  expect(native.completePermissionRequest).not.toHaveBeenCalled();
  expect(native.getHome).toHaveBeenCalledTimes(1);
  expect(screen.queryByText('Permission still off')).toBeNull();
  expect(refresh).toHaveBeenCalled();
});

test('a failed completion-history write does not stop permission refresh', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, callback: (state: string) => void) => {
    listeners.push(callback);
    return { remove: jest.fn() };
  }) as never);
  native.completePermissionRequest.mockRejectedValueOnce(new Error('history store unavailable'));
  native.getHome.mockRejectedValueOnce(new Error('snapshot unavailable'));
  const refresh = jest.fn();
  await renderHome(home(), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Over other apps, Off, Optional' }));
  await act(async () => {
    AppState.currentState = 'background';
    for (const callback of listeners) await callback('background');
    AppState.currentState = 'active';
    for (const callback of listeners) await callback('active');
  });

  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
  expect(native.getHome).toHaveBeenCalledTimes(1);
  expect(refresh).toHaveBeenCalled();
  expect(screen.queryByText('Permission still off')).toBeNull();
});

test('a native notification prompt failure is reported and releases the request guard', async () => {
  PermissionsAndroid.request = jest.fn(async () => { throw new Error('prompt unavailable'); });
  const refresh = jest.fn();
  await renderHome(homeWithNotifications(), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Notification, Off, Optional' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('Could not read the picture');
  expect(native.beginPermissionRequest).toHaveBeenCalledWith('notifications');
  expect(native.completePermissionRequest).not.toHaveBeenCalled();
  expect(refresh).toHaveBeenCalled();
});

test('notification history completion and refresh failures are swallowed after the OS request', async () => {
  PermissionsAndroid.request = jest.fn(async () => PermissionsAndroid.RESULTS.DENIED);
  native.completePermissionRequest.mockRejectedValueOnce(new Error('history store unavailable'));
  native.getHome.mockRejectedValueOnce(new Error('snapshot unavailable'));
  const refresh = jest.fn();
  await renderHome(homeWithNotifications(), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Notification, Off, Optional' }));

  expect(PermissionsAndroid.request).toHaveBeenCalledTimes(1);
  expect(native.completePermissionRequest).toHaveBeenCalledWith('notifications', 'attempt-1', false);
  expect(refresh).toHaveBeenCalled();
  expect(screen.queryByText('Permission still off')).toBeNull();
});

test('notification permission can continue when history storage is unavailable', async () => {
  native.beginPermissionRequest.mockRejectedValueOnce(new Error('history store unavailable'));
  PermissionsAndroid.request = jest.fn(async () => PermissionsAndroid.RESULTS.GRANTED);
  native.getHome.mockResolvedValue({
    ...homeWithNotifications(),
    rows: [
      { ...home().rows[0], status: 'granted' as const },
      { ...homeWithNotifications().rows[1], status: 'granted' as const },
    ],
  });
  const refresh = jest.fn();
  await renderHome(homeWithNotifications(), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Notification, Off, Optional' }));

  expect(PermissionsAndroid.request).toHaveBeenCalledTimes(1);
  expect(native.beginPermissionRequest).toHaveBeenCalledWith('notifications');
  expect(native.completePermissionRequest).not.toHaveBeenCalled();
  expect(refresh).toHaveBeenCalled();
});

test('the diagnostics notice omits the optional policy action when its host callback is absent', async () => {
  await render(
    <LocaleGate>
      <ThemeProvider mode="light">
        <HomeScreen home={home()} busy={false} notice="" diagnosticsNoticePending onRefresh={jest.fn()} onSettings={jest.fn()} onCut={jest.fn()} />
      </ThemeProvider>
    </LocaleGate>,
  );
  expect(screen.getByText('Diagnostics are enabled by default')).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: 'Privacy policy' })).toBeNull();
  await fireEvent.press(screen.getByRole('button', { name: 'Dismiss' }));
});

test('backgrounding with no pending settings request does not record a permission attempt', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, callback: (state: string) => void) => {
    listeners.push(callback);
    return { remove: jest.fn() };
  }) as never);
  const refresh = jest.fn();
  await renderHome(home(), refresh);

  await act(async () => { for (const callback of listeners) await callback('background'); });

  expect(listeners.length).toBeGreaterThan(0);
  expect(native.completePermissionRequest).not.toHaveBeenCalled();
});

test('a completed settings round trip records one permission attempt and then shows the blocked rationale', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, callback: (state: string) => void) => {
    listeners.push(callback);
    return { remove: jest.fn() };
  }) as never);
  native.getHome.mockResolvedValue(home(2));
  let reenterOnRefresh = false;
  const refresh = jest.fn(() => {
    if (!reenterOnRefresh) return;
    reenterOnRefresh = false;
    for (const callback of listeners) void callback('active');
  });
  await renderHome(home(1), refresh);

  await fireEvent.press(screen.getByRole('button', { name: 'Over other apps, Off, Optional' }));
  await waitFor(() => expect(native.openPermission).toHaveBeenCalledWith('overlay'));
  expect(native.beginPermissionRequest).toHaveBeenCalledTimes(1);

  await act(async () => {
    for (const callback of listeners) await callback('background');
    reenterOnRefresh = true;
    for (const callback of listeners) await callback('active');
  });
  await waitFor(() => expect(screen.getByText('Permission still off')).toBeOnTheScreen());
  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
  expect(native.completePermissionRequest).toHaveBeenCalledWith('overlay', 'attempt-1', false);
  expect(native.getHome).toHaveBeenCalledTimes(1);

  await act(async () => { for (const callback of listeners) await callback('active'); });
  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
});

test('Settings clipboard FAQ explains the focused Android clipboard flow', async () => {
  await render(
    <LocaleGate>
      <ThemeProvider mode="light">
        <SettingsScreen showXiaomiTip={false} onBack={jest.fn()} onLanguage={jest.fn()} />
      </ThemeProvider>
    </LocaleGate>,
  );
  await fireEvent.press(screen.getByText('Clipboard doesn’t show my image'));
  expect(screen.getByText(/Android opens a focused Cutout screen/)).toBeOnTheScreen();
  expect(screen.getByText(/Text-only clipboard items cannot be cut/)).toBeOnTheScreen();
});

test('Feedback keeps the message field bounded and validates without rewriting native text input', async () => {
  await render(
    <LocaleGate>
      <ThemeProvider mode="light"><FeedbackScreen endpoint="https://example.invalid" onBack={jest.fn()} /></ThemeProvider>
    </LocaleGate>,
  );
  const email = screen.getByLabelText('Reply email');
  const message = screen.getByLabelText('Message');
  expect(email.props.value).toBeUndefined();
  expect(message.props.value).toBeUndefined();
  expect(message.props.scrollEnabled).toBe(true);
  expect(message.props.style.maxHeight).toBe(220);

  await fireEvent.changeText(email, 'person@example.com');
  await fireEvent.changeText(message, 'hi');
  expect(screen.getByText('2/4000')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Send feedback' }));
  expect(screen.getByText('Enter between 5 and 4000 characters.')).toBeOnTheScreen();
});

test('Screen applies one keyboard inset strategy per platform', async () => {
  Platform.OS = 'android';
  await render(<ThemeProvider mode="light"><Screen scroll><Text>Android content</Text></Screen></ThemeProvider>);
  expect(screen.getByTestId('screen-scroll')).toHaveProp('automaticallyAdjustKeyboardInsets', false);

  Platform.OS = 'ios';
  await screen.rerender(<ThemeProvider mode="light"><Screen scroll><Text>iOS content</Text></Screen></ThemeProvider>);
  expect(screen.getByTestId('screen-scroll')).toHaveProp('automaticallyAdjustKeyboardInsets', true);
});

test('Screen scrolls a focused field above the keyboard and also renders without scrolling', async () => {
  const scrollToInput = jest.fn();
  let onKeyboardShow: (() => void) | undefined;
  const remove = jest.fn();
  const focusVisibility = createFocusVisibilityController(scrollToInput, listener => {
    onKeyboardShow = listener;
    return { remove };
  });
  focusVisibility.focus(123);
  onKeyboardShow?.();
  expect(scrollToInput).toHaveBeenNthCalledWith(1, 123);
  expect(scrollToInput).toHaveBeenNthCalledWith(2, 123);
  focusVisibility.blur(123);
  onKeyboardShow?.();
  expect(scrollToInput).toHaveBeenCalledTimes(2);
  focusVisibility.dispose();
  expect(remove).toHaveBeenCalledTimes(1);

  await render(<ThemeProvider mode="light"><Screen><Text>Fixed content</Text></Screen></ThemeProvider>);
  expect(screen.getByText('Fixed content')).toBeOnTheScreen();
});

test('Screen repeats focus scrolling after the Android keyboard appears and removes the listener', async () => {
  let onKeyboardShow: (() => void) | undefined;
  const remove = jest.fn();
  jest.spyOn(Keyboard, 'addListener').mockImplementation(((event: string, callback: () => void) => {
    if (event === 'keyboardDidShow') onKeyboardShow = callback;
    return { remove };
  }) as never);
  const view = await render(<ThemeProvider mode="light"><Screen scroll><TextInput accessibilityLabel="Message" /></Screen></ThemeProvider>);
  expect(Keyboard.addListener).toHaveBeenCalledWith('keyboardDidShow', expect.any(Function));
  expect(onKeyboardShow).toEqual(expect.any(Function));
  await view.unmount();
  expect(remove).toHaveBeenCalledTimes(1);
});

test('Sheet can show an accessible permission icon without requiring one on other sheets', async () => {
  await render(
    <ThemeProvider mode="light">
      <Sheet title="Permission needed" body="Description" visible closeLabel="Close" onClose={jest.fn()} icon={<Text>permission icon</Text>} />
    </ThemeProvider>,
  );
  expect(screen.getByText('permission icon')).toBeOnTheScreen();
});
