import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AppState, PermissionsAndroid, Platform, type PermissionStatus } from 'react-native';
import { HomeScreen } from './screens/HomeScreen';
import { LocaleGate } from './i18n/LocaleGate';
import { ThemeProvider } from './theme/ThemeProvider';
import type { HomeSnapshot } from './native/Erase';

declare const global: { __erase: Record<string, jest.Mock> };
const native = global.__erase;

const baseHome: HomeSnapshot = {
  rows: [
    { kind: 'overlay', title: 'Overlay', status: 'denied', need: 'optional' },
    { kind: 'notifications', title: 'Notifications', status: 'denied', need: 'optional' },
  ],
  bubbleEnabled: false,
  showXiaomiTip: false,
  clipboardReady: false,
  bubbleSupported: true,
};

function renderHome(snapshot = baseHome, refresh = jest.fn()) {
  return render(
    <LocaleGate>
      <ThemeProvider mode="light">
        <HomeScreen
          home={snapshot}
          busy={false}
          notice=""
          onRefresh={refresh}
          onSettings={jest.fn()}
          onCut={jest.fn()}
        />
      </ThemeProvider>
    </LocaleGate>,
  );
}

function notificationRow() {
  return screen.getByRole('button', { name: 'Notification, Off, Optional' });
}

function overlayRow() {
  return screen.getByRole('button', { name: 'Over other apps, Off, Optional' });
}

beforeEach(() => {
  jest.clearAllMocks();
  jest.replaceProperty(Platform, 'OS', 'android');
  jest.spyOn(Platform, 'Version', 'get').mockReturnValue(35);
  AppState.currentState = 'active';
  native.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  native.getHome.mockResolvedValue(baseHome);
  native.openPermission.mockResolvedValue(undefined);
  native.setBubble.mockResolvedValue(undefined);
  native.beginPermissionRequest = jest.fn(async () => 'permission-attempt');
  native.completePermissionRequest = jest.fn(async () => undefined);
  PermissionsAndroid.PERMISSIONS = {
    ...PermissionsAndroid.PERMISSIONS,
    POST_NOTIFICATIONS: 'android.permission.POST_NOTIFICATIONS',
  };
});

afterEach(() => {
  jest.restoreAllMocks();
});

test.each([
  { name: 'granted', request: PermissionsAndroid.RESULTS.GRANTED, blocked: false, row: { ...baseHome.rows[1], status: 'granted' as const } },
  { name: 'denied once', request: PermissionsAndroid.RESULTS.DENIED, blocked: false, row: { ...baseHome.rows[1], deniedCount: 1 } },
])('notification runtime permission: $name is recorded once using the resulting native status', async ({ request, blocked, row }) => {
  PermissionsAndroid.request = jest.fn(async () => request);
  native.getHome.mockResolvedValue({ ...baseHome, rows: [baseHome.rows[0], row] });
  const refresh = jest.fn();
  await renderHome(baseHome, refresh);

  await fireEvent.press(notificationRow());

  expect(PermissionsAndroid.request).toHaveBeenCalledWith('android.permission.POST_NOTIFICATIONS');
  expect(native.beginPermissionRequest).toHaveBeenCalledTimes(1);
  expect(native.beginPermissionRequest).toHaveBeenCalledWith('notifications');
  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
  expect(native.completePermissionRequest).toHaveBeenCalledWith('notifications', 'permission-attempt', blocked);
  expect(refresh).toHaveBeenCalled();
  expect(screen.queryByText('Permission still off')).toBeNull();
});

test('never-ask-again is recorded as blocked and opens the direct settings rationale from current history', async () => {
  PermissionsAndroid.request = jest.fn(async () => PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN);
  native.getHome.mockResolvedValue({
    ...baseHome,
    rows: [baseHome.rows[0], { ...baseHome.rows[1], deniedCount: 2, permanentlyDenied: true }],
  });
  await renderHome();

  await fireEvent.press(notificationRow());

  expect(native.completePermissionRequest).toHaveBeenCalledWith('notifications', 'permission-attempt', true);
  expect(await screen.findByText('Permission still off')).toBeOnTheScreen();
  expect(screen.getByRole('button', { name: 'Open settings' })).toBeOnTheScreen();
});

test('a second notification tap while the platform request is pending does not start a parallel request', async () => {
  let resolveRequest: ((result: PermissionStatus) => void) | undefined;
  PermissionsAndroid.request = jest.fn(() => new Promise(resolve => { resolveRequest = resolve; }));
  await renderHome();
  const row = notificationRow();

  await fireEvent.press(row);
  await fireEvent.press(notificationRow());

  expect(PermissionsAndroid.request).toHaveBeenCalledTimes(1);
  expect(native.beginPermissionRequest).toHaveBeenCalledTimes(1);
  await act(async () => {
    resolveRequest?.(PermissionsAndroid.RESULTS.DENIED);
    await Promise.resolve();
  });
  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
});

test('a settings round-trip with a fast background return completes its history id once', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, listener: (state: string) => void | Promise<void>) => {
    listeners.push(listener);
    return { remove: jest.fn() };
  }) as never);
  let resolveHistory: ((attemptId: string) => void) | undefined;
  native.beginPermissionRequest = jest.fn(() => new Promise(resolve => { resolveHistory = resolve; }));
  native.getHome.mockResolvedValue({
    ...baseHome,
    rows: [{ ...baseHome.rows[0], status: 'granted' }, baseHome.rows[1]],
  });
  const refresh = jest.fn();
  await renderHome(baseHome, refresh);

  await fireEvent.press(overlayRow());
  expect(native.openPermission).toHaveBeenCalledWith('overlay');
  await act(async () => {
    AppState.currentState = 'background';
    for (const listener of listeners) await listener('background');
    AppState.currentState = 'active';
    for (const listener of listeners) await listener('active');
  });
  await act(async () => {
    resolveHistory?.('fast-return-attempt');
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
  expect(native.completePermissionRequest).toHaveBeenCalledWith('overlay', 'fast-return-attempt', false);
  expect(refresh).toHaveBeenCalled();
});

test('failed settings launch does not start or complete a permission attempt', async () => {
  native.openPermission.mockRejectedValueOnce(new Error('settings activity unavailable'));
  const refresh = jest.fn();
  await renderHome(baseHome, refresh);

  await fireEvent.press(overlayRow());

  expect(native.beginPermissionRequest).not.toHaveBeenCalled();
  expect(native.completePermissionRequest).not.toHaveBeenCalled();
  expect(screen.getByText('Could not read the picture')).toBeOnTheScreen();
  expect(refresh).toHaveBeenCalled();
});

test('a missing permission row cannot turn a stale app-state return into a completed request', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, listener: (state: string) => void | Promise<void>) => {
    listeners.push(listener);
    return { remove: jest.fn() };
  }) as never);
  await renderHome(baseHome);
  await fireEvent.press(overlayRow());
  native.getHome.mockResolvedValue({ ...baseHome, rows: [baseHome.rows[1]] });

  await act(async () => {
    AppState.currentState = 'background';
    for (const listener of listeners) await listener('background');
    AppState.currentState = 'active';
    for (const listener of listeners) await listener('active');
  });
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });

  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
  expect(native.completePermissionRequest).toHaveBeenCalledWith('overlay', 'permission-attempt', false);
  expect(screen.queryByText('Permission still off')).toBeNull();
});

test('settings return with an unknown grant leaves an optional permission row without inventing denial history', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, listener: (state: string) => void | Promise<void>) => {
    listeners.push(listener);
    return { remove: jest.fn() };
  }) as never);
  const view = await renderHome(baseHome);
  await fireEvent.press(overlayRow());
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  native.getHome.mockResolvedValue({
    ...baseHome,
    rows: [{ ...baseHome.rows[0], status: 'unknown', deniedCount: 0 }, baseHome.rows[1]],
  });

  await act(async () => {
    AppState.currentState = 'background';
    for (const listener of listeners) await listener('background');
    AppState.currentState = 'active';
    for (const listener of listeners) await listener('active');
    await Promise.resolve();
  });

  expect(native.completePermissionRequest).toHaveBeenCalledWith('overlay', 'permission-attempt', false);
  expect(screen.queryByText('Permission still off')).toBeNull();
  await view.rerender(
    <LocaleGate>
      <ThemeProvider mode="light">
        <HomeScreen
          home={{
            ...baseHome,
            rows: [{ ...baseHome.rows[0], status: 'unknown', deniedCount: 0 }, baseHome.rows[1]],
          }}
          busy={false}
          notice=""
          onRefresh={jest.fn()}
          onSettings={jest.fn()}
          onCut={jest.fn()}
        />
      </ThemeProvider>
    </LocaleGate>,
  );
  expect(screen.getByRole('button', { name: 'Over other apps, Unknown, Optional' })).toBeOnTheScreen();
});

test('opening settings from a blocked Bubble rationale completes one pending attempt and keeps Bubble off after denial', async () => {
  const listeners: Array<(state: string) => void | Promise<void>> = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_event: string, listener: (state: string) => void | Promise<void>) => {
    listeners.push(listener);
    return { remove: jest.fn() };
  }) as never);
  const blocked = {
    ...baseHome,
    rows: [{ ...baseHome.rows[0], deniedCount: 2 }, baseHome.rows[1]],
  };
  native.getHome.mockResolvedValue(blocked);
  await renderHome(blocked);
  await act(async () => {
    screen.getByLabelText('Show bubble').props.onChange({ nativeEvent: { value: true } });
  });
  await fireEvent.press(screen.getByRole('button', { name: 'Open settings' }));
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  expect(native.openPermission).toHaveBeenCalledWith('overlay');

  await act(async () => {
    AppState.currentState = 'background';
    for (const listener of listeners) await listener('background');
    AppState.currentState = 'active';
    for (const listener of listeners) await listener('active');
  });

  expect(native.completePermissionRequest).toHaveBeenCalledTimes(1);
  expect(native.completePermissionRequest).toHaveBeenCalledWith('overlay', 'permission-attempt', false);
  expect(native.setBubble).not.toHaveBeenCalled();
  expect(screen.getByText('Permission still off')).toBeOnTheScreen();
  expect(screen.getByRole('button', { name: 'Over other apps, Off, Optional' })).toBeOnTheScreen();
});

test('closing blocked overlay rationale cancels pending Bubble enable and leaves the permission optional', async () => {
  const blocked = {
    ...baseHome,
    rows: [{ ...baseHome.rows[0], deniedCount: 2 } as HomeSnapshot['rows'][number], baseHome.rows[1]],
  };
  await renderHome(blocked);

  const bubbleSwitch = screen.getByLabelText('Show bubble');
  await act(async () => { bubbleSwitch.props.onChange({ nativeEvent: { value: true } }); });
  expect(screen.getByText('Permission still off')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Not now' }));

  expect(screen.queryByText('Permission still off')).toBeNull();
  expect(screen.getByRole('button', { name: 'Over other apps, Off, Optional' })).toBeOnTheScreen();
  expect(native.setBubble).not.toHaveBeenCalled();
});
