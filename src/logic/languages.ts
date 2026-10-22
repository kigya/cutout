export const LOCALE_CODES = [
  'en',
  'zh-Hans',
  'zh-Hant',
  'hi',
  'es',
  'fr',
  'ar',
  'bn',
  'pt',
  'ru',
  'ur',
  'id',
  'de',
  'ja',
  'tr',
  'vi',
  'ko',
  'it',
  'th',
  'pl',
  'uk',
  'nl',
  'fa',
  'ms',
  'fil',
  'ro',
  'el',
  'he',
  'sv',
  'cs',
] as const;

export type LocaleCode = (typeof LOCALE_CODES)[number];

export type LanguageChoice = {
  code: LocaleCode;
  nativeName: string;
  flag: string;
};

export const LANGUAGES: readonly LanguageChoice[] = [
  { code: 'ar', nativeName: 'العربية', flag: '🇸🇦' },
  { code: 'bn', nativeName: 'বাংলা', flag: '🇧🇩' },
  { code: 'cs', nativeName: 'Čeština', flag: '🇨🇿' },
  { code: 'de', nativeName: 'Deutsch', flag: '🇩🇪' },
  { code: 'el', nativeName: 'Ελληνικά', flag: '🇬🇷' },
  { code: 'en', nativeName: 'English', flag: '🇬🇧' },
  { code: 'es', nativeName: 'Español', flag: '🇪🇸' },
  { code: 'fa', nativeName: 'فارسی', flag: '🇮🇷' },
  { code: 'fil', nativeName: 'Filipino', flag: '🇵🇭' },
  { code: 'fr', nativeName: 'Français', flag: '🇫🇷' },
  { code: 'he', nativeName: 'עברית', flag: '🇮🇱' },
  { code: 'hi', nativeName: 'हिन्दी', flag: '🇮🇳' },
  { code: 'id', nativeName: 'Bahasa Indonesia', flag: '🇮🇩' },
  { code: 'it', nativeName: 'Italiano', flag: '🇮🇹' },
  { code: 'ja', nativeName: '日本語', flag: '🇯🇵' },
  { code: 'ko', nativeName: '한국어', flag: '🇰🇷' },
  { code: 'ms', nativeName: 'Bahasa Melayu', flag: '🇲🇾' },
  { code: 'nl', nativeName: 'Nederlands', flag: '🇳🇱' },
  { code: 'pl', nativeName: 'Polski', flag: '🇵🇱' },
  { code: 'pt', nativeName: 'Português', flag: '🇧🇷' },
  { code: 'ro', nativeName: 'Română', flag: '🇷🇴' },
  { code: 'ru', nativeName: 'Русский', flag: '🇷🇺' },
  { code: 'sv', nativeName: 'Svenska', flag: '🇸🇪' },
  { code: 'th', nativeName: 'ไทย', flag: '🇹🇭' },
  { code: 'tr', nativeName: 'Türkçe', flag: '🇹🇷' },
  { code: 'uk', nativeName: 'Українська', flag: '🇺🇦' },
  { code: 'ur', nativeName: 'اردو', flag: '🇵🇰' },
  { code: 'vi', nativeName: 'Tiếng Việt', flag: '🇻🇳' },
  { code: 'zh-Hans', nativeName: '中文（简体）', flag: '🇨🇳' },
  { code: 'zh-Hant', nativeName: '中文（繁體）', flag: '🇹🇼' },
];

const SUPPORTED = new Set<string>(LOCALE_CODES);
const RTL = new Set<LocaleCode>(['ar', 'fa', 'ur', 'he']);

export function sortLanguages(languages: readonly LanguageChoice[] = LANGUAGES): LanguageChoice[] {
  return [...languages].sort((left, right) =>
    left.nativeName.localeCompare(right.nativeName, 'en', { sensitivity: 'base' }),
  );
}

export function isRtl(code: LocaleCode): boolean {
  return RTL.has(code);
}

export function resolveLocale(tag: string): LocaleCode {
  const normalized = tag.replace(/_/g, '-');
  const lower = normalized.toLowerCase();
  if (lower === 'zh-hans' || lower === 'zh-cn' || lower === 'zh-sg') return 'zh-Hans';
  if (lower === 'zh-hant' || lower === 'zh-tw' || lower === 'zh-hk' || lower === 'zh-mo') return 'zh-Hant';
  if (lower.startsWith('pt')) return 'pt';
  const dash = lower.indexOf('-');
  const language = dash === -1 ? lower : lower.slice(0, dash);
  if (language === 'zh') return 'zh-Hans';
  if (language === 'he' || language === 'iw') return 'he';
  if (language === 'in') return 'id';
  if (language === 'tl' || language === 'fil') return 'fil';
  if (SUPPORTED.has(normalized)) return normalized as LocaleCode;
  if (SUPPORTED.has(language)) return language as LocaleCode;
  return 'en';
}
