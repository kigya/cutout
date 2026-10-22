import {
  createFeedbackAttempt,
  createFeedbackMailto,
  runFeedbackSubmissionOnce,
  submitFeedback,
  validateFeedback,
  type FeedbackPayload,
} from './feedback';

const payload: FeedbackPayload = {
  appId: 'dev.kigya.cutout',
  email: 'person@example.com',
  message: 'The edge brush is hard to use.',
  rating: 0,
  id: '00000000-0000-4000-8000-000000000001',
  version: '1.0.0',
  platform: 'android',
};

describe('feedback submission', () => {
  test('guards overlapping submissions until the active attempt releases the lock', async () => {
    const lock = { current: false };
    let resolveFirst: (() => void) | undefined;
    const first = runFeedbackSubmissionOnce(lock, () => new Promise(resolve => { resolveFirst = resolve; }));
    expect(await runFeedbackSubmissionOnce(lock, async () => undefined)).toBe(false);
    resolveFirst?.();
    await expect(first).resolves.toBe(true);
    expect(await runFeedbackSubmissionOnce(lock, async () => undefined)).toBe(true);
    expect(lock.current).toBe(false);
  });

  test('validates trimmed email and message limits', () => {
    expect(validateFeedback(' person@example.com ', '  hello  ')).toEqual({ email: true, message: true });
    expect(validateFeedback('not an email', 'four')).toEqual({ email: false, message: false });
    expect(validateFeedback('person@example.com', 'x'.repeat(4001))).toEqual({ email: true, message: false });
    expect(validateFeedback(`${'x'.repeat(250)}@example.com`, 'valid text')).toEqual({ email: false, message: true });
  });

  test('reuses the UUID for the exact retry payload and rotates it for an edit', () => {
    const makeId = jest.fn().mockReturnValueOnce(payload.id).mockReturnValueOnce('00000000-0000-4000-8000-000000000002');
    const first = createFeedbackAttempt(' person@example.com ', ' The edge brush is hard to use. ', 'android', undefined, makeId);
    const retry = createFeedbackAttempt('person@example.com', 'The edge brush is hard to use.', 'android', first, makeId);
    const edited = createFeedbackAttempt('person@example.com', 'The edge brush is difficult.', 'android', retry, makeId);
    expect(retry).toBe(first);
    expect(edited.payload.id).not.toBe(first.payload.id);
    expect(edited.payload).toEqual({ ...payload, id: '00000000-0000-4000-8000-000000000002', message: 'The edge brush is difficult.' });
    expect(makeId).toHaveBeenCalledTimes(2);
  });

  test('generates UUIDv4 IDs when there is no previous attempt', () => {
    const attempt = createFeedbackAttempt('person@example.com', 'Please improve this.', 'ios');
    expect(attempt.payload.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  test('uses the platform randomUUID implementation when available', () => {
    const previous = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { randomUUID: () => payload.id } });
    try {
      expect(createFeedbackAttempt('person@example.com', 'A valid message.', 'android').payload.id).toBe(payload.id);
    } finally {
      if (previous) Object.defineProperty(globalThis, 'crypto', previous);
      else Reflect.deleteProperty(globalThis, 'crypto');
    }
  });

  test('uses getRandomValues when randomUUID is unavailable', () => {
    const previous = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    Object.defineProperty(globalThis, 'crypto', {
      configurable: true,
      value: { getRandomValues: (bytes: Uint8Array) => { bytes.fill(0); return bytes; } },
    });
    try {
      const id = createFeedbackAttempt('person@example.com', 'A valid message.', 'android').payload.id;
      expect(id).toBe('00000000-0000-4000-8000-000000000000');
    } finally {
      if (previous) Object.defineProperty(globalThis, 'crypto', previous);
      else Reflect.deleteProperty(globalThis, 'crypto');
    }
  });

  test('uses the UUIDv4 fallback when platform crypto is unavailable', () => {
    const previousCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    const random = jest.spyOn(Math, 'random').mockReturnValue(0.5);
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: undefined });
    try {
      expect(createFeedbackAttempt('person@example.com', 'A valid message.', 'android').payload.id)
        .toBe('88888888-8888-4888-8888-888888888888');
    } finally {
      random.mockRestore();
      if (previousCrypto) Object.defineProperty(globalThis, 'crypto', previousCrypto);
      else Reflect.deleteProperty(globalThis, 'crypto');
    }
  });

  test('posts text-only feedback and requires the server to echo the same id', async () => {
    const fetcher = jest.fn(async () => ({ ok: true, json: async () => ({ ok: true, id: payload.id }) } as Response));
    await expect(submitFeedback('https://example.test/feedback', payload, fetcher)).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenCalledWith('https://example.test/feedback', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: expect.any(AbortSignal),
    }));
  });

  test('uses the runtime fetch implementation when no fetcher is supplied', async () => {
    const fetcher = jest.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: async () => ({ ok: true, id: payload.id }),
    } as Response);
    await expect(submitFeedback('https://example.test/feedback', payload)).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  test('rejects non-ok, malformed, mismatched-id, and non-success responses', async () => {
    const responses = [
      { ok: false, json: async () => ({ ok: true, id: payload.id }) },
      { ok: true, json: async () => ({ ok: true, id: 'other-id' }) },
      { ok: true, json: async () => ({ ok: false, id: payload.id }) },
      { ok: true, json: async () => { throw new Error('bad json'); } },
    ];
    for (const response of responses) {
      const fetcher = jest.fn(async () => response as Response);
      await expect(submitFeedback('https://example.test/feedback', payload, fetcher)).rejects.toThrow('response');
    }
  });

  test('surfaces network failure and abort timeout distinctly', async () => {
    await expect(submitFeedback('https://example.test/feedback', payload, jest.fn().mockRejectedValue('offline'))).rejects.toThrow('network');
    jest.useFakeTimers();
    const fetcher = jest.fn((_url: RequestInfo | URL, options?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => {
        const error = new Error('aborted');
        error.name = 'AbortError';
        reject(error);
      });
    }));
    const pending = submitFeedback('https://example.test/feedback', payload, fetcher, 50);
    const timeoutAssertion = expect(pending).rejects.toThrow('timeout');
    await jest.advanceTimersByTimeAsync(50);
    await timeoutAssertion;
    jest.useRealTimers();
  });

  test('builds a mailto fallback with only the explicit text fields', () => {
    const url = createFeedbackMailto(' person@example.com ', '  Please fix the brush. ');
    expect(url).toContain('mailto:kirillborichevskiy@gmail.com?');
    expect(decodeURIComponent(url)).toContain('person@example.com');
    expect(decodeURIComponent(url)).toContain('Please fix the brush.');
    expect(decodeURIComponent(url)).toContain('dev.kigya.cutout');
    expect(url).not.toContain('image');
  });
});
