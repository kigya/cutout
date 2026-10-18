import { act } from '@testing-library/react-native';
import { AppState, DeviceEventEmitter, Platform } from 'react-native';
import { Erase, type MainActivitySnapshot } from './Erase';
import { MainAppState } from './MainAppState';

const originalPlatform = Platform.OS;

test('native startup has no main-activity state until a snapshot arrives; unavailable RN state is null', () => {
  Platform.OS = 'android';
  Erase.getMainActivityState = jest.fn(() => new Promise(() => undefined));
  expect(MainAppState.currentState).toBeNull();
});
afterEach(() => {
  Platform.OS = originalPlatform;
  delete Erase.getMainActivityState;
});

test('screen activity follows native MainActivity events and rejects a late initial snapshot', async () => {
  Platform.OS = 'android';
  let resolve!: (state: MainActivitySnapshot) => void;
  Erase.getMainActivityState = jest.fn(() => new Promise(next => { resolve = next; }));
  const listener = jest.fn();
  const subscription = MainAppState.addEventListener('change', listener);
  await act(async () => {
    DeviceEventEmitter.emit('mainActivityState', { revision: 2, visible: false, resumed: false });
    resolve({ revision: 1, visible: true, resumed: true });
    await Promise.resolve();
  });
  expect(listener.mock.calls).toEqual([['background']]);
  expect(MainAppState.currentState).toBe('background');
  await act(async () => {
    DeviceEventEmitter.emit('mainActivityState', { revision: 3, visible: true, resumed: true });
  });
  expect(listener.mock.calls).toEqual([['background'], ['active']]);
  expect(MainAppState.currentState).toBe('active');
  subscription.remove();
  DeviceEventEmitter.emit('mainActivityState', { revision: 4, visible: false, resumed: false });
  expect(listener).toHaveBeenCalledTimes(2);
});

test('iOS and bridges without the Android method use RN AppState', () => {
  Platform.OS = 'ios';
  expect(MainAppState.currentState).toBe(AppState.currentState);
  const remove = jest.fn();
  const spy = jest.spyOn(AppState, 'addEventListener').mockReturnValue({ remove });
  const listener = jest.fn();
  const subscription = MainAppState.addEventListener('change', listener);
  expect(spy).toHaveBeenCalledWith('change', listener);
  subscription.remove();
  expect(remove).toHaveBeenCalledTimes(1);
  spy.mockRestore();
});


test('native event deliveries keep the newest global snapshot and ignore queued callbacks after removal', async () => {
  Platform.OS = 'android';
  Erase.getMainActivityState = jest.fn(() => Promise.reject(new Error('not ready')));
  let receive!: (value: MainActivitySnapshot) => void;
  const add = jest.spyOn(DeviceEventEmitter, 'addListener').mockImplementation((_name, listener) => {
    receive = listener; return { remove: jest.fn() } as never;
  });
  const listener = jest.fn();
  const sub = MainAppState.addEventListener('change', listener);
  receive({ revision: 0, visible: true, resumed: false });
  receive({ revision: 1, visible: true, resumed: false });
  expect(listener).toHaveBeenCalledTimes(1);
  sub.remove();
  receive({ revision: 100, visible: true, resumed: true });
  await Promise.resolve();
  expect(listener).toHaveBeenCalledTimes(1); add.mockRestore();
  Platform.OS = 'ios';
  const prior = AppState.currentState; AppState.currentState = null;
  expect(MainAppState.currentState).toBeNull(); AppState.currentState = prior;
});
