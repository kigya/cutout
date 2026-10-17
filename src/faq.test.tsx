import { fireEvent, render, screen } from '@testing-library/react-native';
import { Platform } from 'react-native';
import { LocaleGate } from './i18n/LocaleGate';
import { text } from './i18n/catalogs';
import { SettingsScreen } from './screens/SettingsScreen';

test.each([
  ['faqSaveTitle', 'faqSaveBody'],
  ['faqEditorTitle', 'faqEditorBody'],
  ['faqModelTitle', 'faqModelBody'],
] as const)('the %s FAQ opens the corresponding guidance and can be dismissed', async (title, body) => {
  await render(<LocaleGate><SettingsScreen showXiaomiTip={false} onBack={() => undefined} onLanguage={() => undefined} /></LocaleGate>);
  await fireEvent.press(screen.getByRole('button', { name: text('en', title) }));
  expect(screen.getByText(text('en', body))).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'OK' }));
  expect(screen.queryByText(text('en', body))).toBeNull();
});

test.each([
  ['android', 'faqClipboardBody'],
  ['ios', 'faqClipboardBodyIos'],
] as const)('clipboard FAQ uses the %s-specific paste guidance', async (platform, body) => {
  const previousPlatform = Platform.OS;
  Platform.OS = platform;
  try {
    await render(<LocaleGate><SettingsScreen showXiaomiTip={false} onBack={() => undefined} onLanguage={() => undefined} /></LocaleGate>);
    await fireEvent.press(screen.getByRole('button', { name: text('en', 'faqClipboardTitle') }));
    expect(screen.getByText(text('en', body))).toBeOnTheScreen();
  } finally {
    Platform.OS = previousPlatform;
  }
});
