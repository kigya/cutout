import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Keyboard, Linking, Platform } from 'react-native';
import { LocaleGate } from '../i18n/LocaleGate';
import { ThemeProvider } from '../theme/ThemeProvider';
import { FeedbackScreen } from './FeedbackScreen';

let view: Awaited<ReturnType<typeof render>>;

async function showScreen(onBack = jest.fn(), timeoutMs = 30_000) {
  const feedbackScreen = timeoutMs === 30_000
    ? <FeedbackScreen endpoint="https://example.test/feedback" onBack={onBack} />
    : <FeedbackScreen endpoint="https://example.test/feedback" onBack={onBack} timeoutMs={timeoutMs} />;
  view = await render(
    <ThemeProvider mode="light">
      <LocaleGate>{feedbackScreen}</LocaleGate>
    </ThemeProvider>,
  );
}

async function fillValidForm() {
  await fireEvent.changeText(view.getByLabelText('Reply email'), 'person@example.com');
  await fireEvent.changeText(view.getByLabelText('Message'), 'The edge brush is hard to use.');
}

describe('FeedbackScreen', () => {
  afterEach(() => { jest.useRealTimers(); jest.restoreAllMocks(); });

  test('shows clear text-only disclosure, validates before sending, and reports success', async () => {
    const fetcher = jest.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as { id: string };
      return { ok: true, json: async () => ({ ok: true, id: body.id }) } as Response;
    });
    await showScreen();
    expect(view.getByText('This sends your message and reply email to Cutout support. Do not include images or other sensitive information.')).toBeTruthy();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    expect(view.getByText('Enter a valid email address.')).toBeTruthy();
    expect(view.getByText('Enter between 5 and 4000 characters.')).toBeTruthy();
    expect(fetcher).not.toHaveBeenCalled();
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    await waitFor(() => expect(view.getAllByText('Feedback sent.').length).toBeGreaterThan(0));
    const request = JSON.parse(String(fetcher.mock.calls[0][1]?.body));
    expect(request).toMatchObject({ appId: 'dev.kigya.cutout', email: 'person@example.com', rating: 0, version: '1.0.0' });
    expect(request.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(request.message).toBe('The edge brush is hard to use.');
    const sentButton = view.getByRole('button', { name: 'Feedback sent.' });
    expect(sentButton.props.accessibilityState.disabled).toBe(true);
    await fireEvent.press(sentButton);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  test('allows a new explicit submission after the user edits a successful form', async () => {
    const fetcher = jest.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as { id: string };
      return { ok: true, json: async () => ({ ok: true, id: body.id }) } as Response;
    });
    await showScreen();
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    await waitFor(() => expect(view.getAllByText('Feedback sent.').length).toBeGreaterThan(0));

    await fireEvent.changeText(view.getByLabelText('Message'), 'The edge brush needs a clearer target.');
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    const first = JSON.parse(String(fetcher.mock.calls[0][1]?.body)) as { id: string };
    const second = JSON.parse(String(fetcher.mock.calls[1][1]?.body)) as { id: string };
    expect(second.id).not.toBe(first.id);
  });

  test('prevents duplicate taps while a request is pending', async () => {
    let resolveFetch: ((response: Response) => void) | undefined;
    const fetcher = jest.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise(resolve => { resolveFetch = resolve; }));
    await showScreen();
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    await fireEvent.press(view.getByRole('button', { name: 'Sending…' }));
    expect(fetcher).toHaveBeenCalledTimes(1);
    const body = JSON.parse(String(fetcher.mock.calls[0][1]?.body)) as { id: string };
    resolveFetch?.({ ok: true, json: async () => ({ ok: true, id: body.id }) } as Response);
    await waitFor(() => expect(view.getAllByText('Feedback sent.').length).toBeGreaterThan(0));
  });

  test('offers explicit email fallback after a server rejection', async () => {
    jest.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, json: async () => ({ ok: false, code: 'INVALID' }) } as Response);
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    await showScreen();
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    await waitFor(() => expect(view.getByText('Could not send feedback. You can retry or use email.')).toBeTruthy());
    await fireEvent.press(view.getByRole('button', { name: 'Open email' }));
    await waitFor(() => expect(openURL).toHaveBeenCalledWith(expect.stringContaining('mailto:kirillborichevskiy@gmail.com')));
  });

  test('warns about possible duplicate delivery after timeout and reports unavailable email app', async () => {
    jest.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        const error = new Error('aborted');
        error.name = 'AbortError';
        reject(error);
      });
    }));
    const openURL = jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error('no email handler'));
    await showScreen(jest.fn(), 20);
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    await waitFor(() => expect(view.getByText("We couldn't confirm delivery. Retrying may send this message twice.")).toBeTruthy());
    await fireEvent.press(view.getByRole('button', { name: 'Open email' }));
    await act(async () => { await Promise.resolve(); });
    expect(view.getByText('No email app is available. Contact kirillborichevskiy@gmail.com.')).toBeTruthy();
    expect(openURL).toHaveBeenCalled();
  });

  test.each([
    ['network failure', () => Promise.reject(new TypeError('offline'))],
    ['mismatched response', async () => ({ ok: true, json: async () => ({ ok: true, id: 'different-id' }) } as unknown as Response)],
    ['malformed response', async () => ({ ok: true, json: async () => { throw new Error('bad json'); } } as unknown as Response)],
  ])('warns that delivery is uncertain after an unconfirmed %s', async (_name, implementation) => {
    jest.spyOn(globalThis, 'fetch').mockImplementation(implementation as typeof fetch);
    await showScreen();
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    await waitFor(() => expect(view.getByText("We couldn't confirm delivery. Retrying may send this message twice.")).toBeTruthy());
    expect(view.getByRole('button', { name: 'Open email' })).toBeTruthy();
  });

  test('aborts a pending request on unmount without showing a delivery claim', async () => {
    let signal: AbortSignal | undefined;
    jest.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => {
      signal = init?.signal as AbortSignal;
      return new Promise((_resolve, reject) => {
        signal?.addEventListener('abort', () => {
          const error = new Error('aborted');
          error.name = 'AbortError';
          reject(error);
        });
      });
    });
    await showScreen();
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    expect(signal?.aborted).toBe(false);
    await view.unmount();
    expect(signal?.aborted).toBe(true);
    await Promise.resolve();
  });

  test('ignores a late success that arrives after the screen unmounts', async () => {
    let resolveFetch: ((response: Response) => void) | undefined;
    jest.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise(resolve => { resolveFetch = resolve; }));
    await showScreen();
    await fillValidForm();
    await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
    const body = JSON.parse(String((globalThis.fetch as jest.Mock).mock.calls[0][1]?.body)) as { id: string };
    await view.unmount();
    resolveFetch?.({ ok: true, json: async () => ({ ok: true, id: body.id }) } as Response);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  });

  test('sends the platform from the runtime and invokes back navigation', async () => {
    const onBack = jest.fn();
    const fetcher = jest.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as { id: string };
      return { ok: true, json: async () => ({ ok: true, id: body.id }) } as Response;
    });
    const oldPlatform = Platform.OS;
    Object.defineProperty(Platform, 'OS', { configurable: true, value: 'ios' });
    try {
      const dismiss = jest.spyOn(Keyboard, 'dismiss').mockImplementation(() => {});
      await showScreen(onBack);
      await fireEvent(view.getByLabelText('Message'), 'submitEditing');
      await fireEvent(view.getByLabelText('Reply email'), 'submitEditing');
      expect(dismiss).toHaveBeenCalledTimes(2);
      await fillValidForm();
      await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
      await waitFor(() => expect(view.getAllByText('Feedback sent.').length).toBeGreaterThan(0));
      expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body)).platform).toBe('ios');
      await fireEvent.press(view.getByRole('button', { name: 'Back' }));
      expect(onBack).toHaveBeenCalledTimes(1);
    } finally {
      Object.defineProperty(Platform, 'OS', { configurable: true, value: oldPlatform });
    }
  });

  test('uses the Android platform when running on Android', async () => {
    const oldPlatform = Platform.OS;
    Object.defineProperty(Platform, 'OS', { configurable: true, value: 'android' });
    const fetcher = jest.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as { id: string };
      return { ok: true, json: async () => ({ ok: true, id: body.id }) } as Response;
    });
    try {
      await showScreen();
      await fillValidForm();
      await fireEvent.press(view.getByRole('button', { name: 'Send feedback' }));
      await waitFor(() => expect(view.getAllByText('Feedback sent.').length).toBeGreaterThan(0));
      expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body)).platform).toBe('android');
    } finally {
      Object.defineProperty(Platform, 'OS', { configurable: true, value: oldPlatform });
    }
  });
});
