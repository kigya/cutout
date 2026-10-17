import { Erase, type AppPreferences } from '../native/Erase';

export const DEFAULT_PREFERENCES: AppPreferences = { theme: 'system', analytics: false, crashes: false };
export const PRODUCT_EVENTS = ['screen_view', 'screen_duration', 'cutout_started', 'cutout_completed', 'cutout_failed', 'cutout_cancelled', 'editor_action', 'export_completed', 'export_failed', 'bubble_changed', 'permission_result', 'locale_changed'] as const;
export type ProductEvent = typeof PRODUCT_EVENTS[number];
export type EventValues = Partial<{
  screen_name: string; duration_ms: number; source: string; surface: string;
  format: string; action: string; result: string; permission: string;
  enabled: boolean; locale: string; stage: string;
}>;

export async function readPreferences(): Promise<AppPreferences> {
  try {
    const saved = await Erase.getPreferences?.();
    if (!saved) return DEFAULT_PREFERENCES;
    return { theme: ['system', 'light', 'dark'].includes(saved.theme) ? saved.theme : 'system', analytics: saved.analytics === true, crashes: saved.crashes === true };
  } catch { return DEFAULT_PREFERENCES; }
}

export async function writePreferences(value: AppPreferences): Promise<void> {
  await Erase.setPreferences?.(value.theme, value.analytics, value.crashes);
}

/** The native boundary validates the same allowlist and checks persisted consent again. */
export function track(event: ProductEvent, values: EventValues = {}): void {
  void Erase.logProductEvent?.(event, values as Record<string, string | number | boolean>).catch(() => undefined);
}
