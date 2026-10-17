import type { LocaleCode } from '../logic/languages';
import { ar } from './messages/ar';
import { bn } from './messages/bn';
import { cs } from './messages/cs';
import { de } from './messages/de';
import { el } from './messages/el';
import { en, type MessageKey, type Messages } from './messages/en';
import { es } from './messages/es';
import { fa } from './messages/fa';
import { fil } from './messages/fil';
import { fr } from './messages/fr';
import { he } from './messages/he';
import { hi } from './messages/hi';
import { id } from './messages/id';
import { it } from './messages/it';
import { ja } from './messages/ja';
import { ko } from './messages/ko';
import { ms } from './messages/ms';
import { nl } from './messages/nl';
import { pl } from './messages/pl';
import { pt } from './messages/pt';
import { ro } from './messages/ro';
import { ru } from './messages/ru';
import { sv } from './messages/sv';
import { th } from './messages/th';
import { tr } from './messages/tr';
import { uk } from './messages/uk';
import { ur } from './messages/ur';
import { vi } from './messages/vi';
import { zhHans } from './messages/zh-Hans';
import { zhHant } from './messages/zh-Hant';

export const catalogs: Record<LocaleCode, Messages> = {
  en,
  'zh-Hans': zhHans,
  'zh-Hant': zhHant,
  hi,
  es,
  fr,
  ar,
  bn,
  pt,
  ru,
  ur,
  id,
  de,
  ja,
  tr,
  vi,
  ko,
  it,
  th,
  pl,
  uk,
  nl,
  fa,
  ms,
  fil,
  ro,
  el,
  he,
  sv,
  cs,
};

const codes: Record<string, MessageKey> = {
  busy: 'busy',
  copied: 'copied',
  svg_as_png: 'svgAsPng',
  model_failed: 'modelFailed',
  read_failed: 'readFailed',
  manual_cut: 'manualCut',
  empty_clipboard: 'emptyClipboard',
  picker_failed: 'pickerFailed',
  cancelled: 'cancelled',
};

export function catalogText(value: string | undefined, fallback: string): string {
  if (!value) return fallback;
  return value;
}

export function text(locale: LocaleCode, key: MessageKey): string {
  return catalogText(catalogs[locale][key], en[key]);
}

export function localizeMessage(locale: LocaleCode, code?: string | null): string {
  if (!code) return '';
  const key = codes[code];
  return key ? text(locale, key) : code;
}
