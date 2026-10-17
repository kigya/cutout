import type { ComponentType } from 'react';
import { act, render, screen } from '@testing-library/react-native';
import { AppRegistry, DeviceEventEmitter } from 'react-native';
import App from './App';

jest.mock('../global.css', () => ({}));
jest.mock('./bubble/BubbleApp', () => {
  const React = require('react');
  const { Text } = require('react-native');
  const { useTheme } = require('./theme/ThemeProvider');
  return { BubbleApp: () => React.createElement(Text, { testID: 'bubble-theme' }, useTheme().mode) };
});

const erase = (globalThis as unknown as { __erase: Record<string, jest.Mock> }).__erase;
const registrations = new Map<string, () => ComponentType>();
jest.spyOn(AppRegistry, 'registerComponent').mockImplementation((name, provider) => {
  registrations.set(name, provider);
  return name;
});
require('../index');
const BubbleRoot = registrations.get('Bubble')!();

beforeEach(() => {
  erase.getLocale.mockResolvedValue({ selected: '', system: 'en' });
  erase.getPreferences = jest.fn(async () => ({ theme: 'light', analytics: false, crashes: false }));
});

async function emitPreferences(value: unknown) {
  await act(async () => { DeviceEventEmitter.emit('preferencesChanged', value); });
}

test('native entry points register the app and keep Bubble appearance synchronized', async () => {
  expect([...registrations.keys()]).toEqual(['Erase', 'Bubble']);
  expect(registrations.get('Erase')!()).toBe(App);
  await render(<BubbleRoot />);
  expect(screen.getByTestId('bubble-theme')).toHaveTextContent('light');
  await emitPreferences({ theme: 'dark', analytics: true, crashes: true });
  expect(screen.getByTestId('bubble-theme')).toHaveTextContent('dark');
  await emitPreferences({ theme: 'light', analytics: false, crashes: false });
  expect(screen.getByTestId('bubble-theme')).toHaveTextContent('light');
  await emitPreferences({ theme: 'invalid' });
  expect(screen.getByTestId('bubble-theme')).toHaveTextContent('system');
  await emitPreferences(null);
  expect(screen.getByTestId('bubble-theme')).toHaveTextContent('system');
});

test('late initial preferences never replace a newer native update', async () => {
  let resolveSaved!: (preferences: unknown) => void;
  erase.getPreferences.mockImplementationOnce(() => new Promise(resolve => { resolveSaved = resolve; }));
  await render(<BubbleRoot />);
  await emitPreferences({ theme: 'dark' });
  await act(async () => { resolveSaved({ theme: 'light' }); });
  expect(screen.getByTestId('bubble-theme')).toHaveTextContent('dark');
});

test('Bubble unregisters preference updates before a late initial read completes', async () => {
  let resolveSaved!: (preferences: unknown) => void;
  erase.getPreferences.mockImplementationOnce(() => new Promise(resolve => { resolveSaved = resolve; }));
  const listenersBefore = DeviceEventEmitter.listenerCount('preferencesChanged');
  await render(<BubbleRoot />);
  expect(DeviceEventEmitter.listenerCount('preferencesChanged')).toBe(listenersBefore + 1);
  await screen.unmount();
  expect(DeviceEventEmitter.listenerCount('preferencesChanged')).toBe(listenersBefore);
  await act(async () => { resolveSaved({ theme: 'light' }); });
});
