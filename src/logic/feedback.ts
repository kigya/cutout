export const FEEDBACK_APP_ID = 'dev.kigya.cutout';
export const FEEDBACK_APP_VERSION = '1.0.0';
export const FEEDBACK_REPLY_TO = 'kirillborichevskiy@gmail.com';

export type FeedbackPlatform = 'android' | 'ios';

export type FeedbackPayload = {
  appId: typeof FEEDBACK_APP_ID;
  email: string;
  message: string;
  rating: 0;
  id: string;
  version: typeof FEEDBACK_APP_VERSION;
  platform: FeedbackPlatform;
};

export type FeedbackAttempt = {
  fingerprint: string;
  payload: FeedbackPayload;
};

export type FeedbackValidation = {
  email: boolean;
  message: boolean;
};

export type FeedbackFailure = 'timeout' | 'network' | 'response' | 'rejected';

const DEFINITE_REJECTIONS = new Set(['INVALID', 'BUSY', 'RATE_LIMIT', 'QUOTA']);

export async function runFeedbackSubmissionOnce(
  lock: { current: boolean },
  submit: () => Promise<void>,
): Promise<boolean> {
  if (lock.current) return false;
  lock.current = true;
  try {
    await submit();
    return true;
  } finally {
    lock.current = false;
  }
}

export function validateFeedback(email: string, message: string): FeedbackValidation {
  const normalizedEmail = email.trim();
  const normalizedMessage = message.trim();
  return {
    email: normalizedEmail.length <= 254 && /^[^\s@<>\r\n]+@[^\s@<>\r\n]+\.[^\s@<>\r\n]+$/.test(normalizedEmail),
    message: normalizedMessage.length >= 5 && normalizedMessage.length <= 4000,
  };
}

export function createFeedbackAttempt(
  email: string,
  message: string,
  platform: FeedbackPlatform,
  previous?: FeedbackAttempt,
  makeId: () => string = createUuid,
): FeedbackAttempt {
  const normalizedEmail = email.trim();
  const normalizedMessage = message.trim();
  const fingerprint = feedbackFingerprint(normalizedEmail, normalizedMessage, platform);
  if (previous?.fingerprint === fingerprint) return previous;
  return {
    fingerprint,
    payload: {
      appId: FEEDBACK_APP_ID,
      email: normalizedEmail,
      message: normalizedMessage,
      rating: 0,
      id: makeId(),
      version: FEEDBACK_APP_VERSION,
      platform,
    },
  };
}

export function feedbackFingerprint(email: string, message: string, platform: FeedbackPlatform): string {
  return JSON.stringify([FEEDBACK_APP_ID, FEEDBACK_APP_VERSION, platform, email.trim(), message.trim(), 0]);
}

export async function submitFeedback(
  endpoint: string,
  payload: FeedbackPayload,
  fetcher: typeof fetch = fetch,
  timeoutMs = 30_000,
  controller = new AbortController(),
): Promise<void> {
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  try {
    const response = await fetcher(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new Error('response');
    }
    if (isDefiniteRejection(body)) throw new Error('rejected');
    if (!response.ok || !isSuccess(body, payload.id)) throw new Error('response');
  } catch (error) {
    if (timedOut || isAbort(error)) throw new Error('timeout');
    if (error instanceof Error && error.message === 'rejected') throw error;
    if (error instanceof Error && error.message === 'response') throw error;
    throw new Error('network');
  } finally {
    clearTimeout(timeout);
  }
}

export function createFeedbackMailto(email: string, message: string): string {
  const body = `Reply email: ${email.trim()}\nApp: ${FEEDBACK_APP_ID}\nVersion: ${FEEDBACK_APP_VERSION}\n\n${message.trim()}`;
  return `mailto:${FEEDBACK_REPLY_TO}?subject=${encodeURIComponent('Cutout feedback')}&body=${encodeURIComponent(body)}`;
}

function isSuccess(value: unknown, id: string): value is { ok: true; id: string } {
  return typeof value === 'object' && value !== null && 'ok' in value && value.ok === true && 'id' in value && value.id === id;
}

function isDefiniteRejection(value: unknown): boolean {
  return typeof value === 'object' && value !== null && 'ok' in value && value.ok === false
    && 'code' in value && typeof value.code === 'string' && DEFINITE_REJECTIONS.has(value.code);
}

function isAbort(value: unknown): boolean {
  return value instanceof Error && value.name === 'AbortError';
}

function createUuid(): string {
  const cryptoApi = (globalThis as unknown as {
    crypto?: { randomUUID?: () => string; getRandomValues?: (array: Uint8Array) => Uint8Array };
  }).crypto;
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID();
  if (cryptoApi?.getRandomValues) {
    const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map(byte => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
    const random = Math.floor(Math.random() * 16);
    return (character === 'x' ? random : (random & 0x3) | 0x8).toString(16);
  });
}
