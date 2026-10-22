import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Keyboard, TextInput, type KeyboardEvent } from 'react-native';
import { Screen } from './components/ui/screen';

const mockScrollToKeyboard = jest.fn();
jest.mock('react-native/Libraries/Components/ScrollView/ScrollView', () => {
  const React = require('react');
  return { __esModule: true, default: React.forwardRef((props: { children: unknown }, ref: unknown) => {
    React.useImperativeHandle(ref, () => ({ scrollResponderScrollNativeHandleToKeyboard: mockScrollToKeyboard }));
    return React.createElement(require('react-native').View, props, props.children);
  }) };
});

test('focus and keyboard events reach the native scroll adapter and stop after blur or unmount', async () => {
  let keyboardDidShow: (event: KeyboardEvent) => unknown = () => undefined;
  const keyboard: KeyboardEvent = { duration: 250, easing: 'keyboard', isEventFromThisApp: true, startCoordinates: { screenX: 0, screenY: 700, width: 400, height: 0 }, endCoordinates: { screenX: 0, screenY: 400, width: 400, height: 300 } };
  const remove = jest.fn();
  jest.spyOn(Keyboard, 'addListener').mockImplementation((_event, handler) => {
    keyboardDidShow = handler;
    return { remove };
  });
  const view = await render(<Screen scroll><TextInput accessibilityLabel="Message" /></Screen>);
  const scroll = screen.getByTestId('screen-scroll');
  const focus = scroll.props.onFocus;
  const blur = scroll.props.onBlur;
  await fireEvent(screen.getByLabelText('Message'), 'focus', { nativeEvent: { target: 42 } });
  expect(mockScrollToKeyboard).toHaveBeenLastCalledWith(42, 24, true);
  await act(async () => { keyboardDidShow(keyboard); });
  expect(mockScrollToKeyboard).toHaveBeenCalledTimes(2);
  await fireEvent(screen.getByLabelText('Message'), 'blur', { nativeEvent: { target: 7 } });
  await act(async () => { keyboardDidShow(keyboard); });
  expect(mockScrollToKeyboard).toHaveBeenCalledTimes(3);
  await fireEvent(screen.getByLabelText('Message'), 'blur', { nativeEvent: { target: 42 } });
  await act(async () => { keyboardDidShow(keyboard); });
  expect(mockScrollToKeyboard).toHaveBeenCalledTimes(3);
  await view.unmount();
  expect(remove).toHaveBeenCalledTimes(1);
  await act(async () => { focus({ nativeEvent: { target: 42 } }); blur({ nativeEvent: { target: 42 } }); keyboardDidShow(keyboard); });
  expect(mockScrollToKeyboard).toHaveBeenCalledTimes(3);
  jest.restoreAllMocks();
});
