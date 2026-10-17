import { useState } from 'react';
import { Platform, Pressable, Text, View } from 'react-native';
import { BackButton } from '../components/ui/backButton';
import { Notice } from '../components/ui/notice';
import { Screen } from '../components/ui/screen';
import { Sheet } from '../components/ui/sheet';
import { useLocale } from '../i18n/LocaleGate';
import { LICENSES } from '../logic/licenses';
import { useTheme, type ThemeMode } from '../theme/ThemeProvider';

type Props = {
  showXiaomiTip: boolean;
  notice?: string;
  onBack: () => void;
  onLanguage: () => void;
  themeMode?: ThemeMode;
  onThemeModeChange?: (mode: ThemeMode) => void;
  analyticsEnabled?: boolean;
  crashEnabled?: boolean;
  onAnalyticsChange?: (enabled: boolean) => void;
  onCrashChange?: (enabled: boolean) => void;
  onOpenPrivacy?: () => void;
  onFeedback?: () => void;
};

export function SettingsScreen({ notice = '', showXiaomiTip, onBack, onLanguage, themeMode = 'system', onThemeModeChange, analyticsEnabled = true, crashEnabled = true, onAnalyticsChange, onCrashChange, onOpenPrivacy, onFeedback }: Props) {
  const { t } = useLocale();
  const { colors } = useTheme();
  const [sheet, setSheet] = useState<'flow' | 'licenses' | 'bubble' | 'clipboard' | 'save' | 'editor' | 'model' | null>(null);
  const licenses = LICENSES;
  const tip = showXiaomiTip ? t('bubblePinXiaomi') : t('bubblePinGeneric');
  const faq = sheet === 'bubble' || sheet === 'clipboard' || sheet === 'save' || sheet === 'editor' || sheet === 'model' ? sheet : null;
  const body = sheet === 'flow'
    ? t('flow')
    : sheet === 'licenses'
      ? licenses.map(entry => `${entry.name} · ${entry.license}`).join('\n')
      : faq === 'bubble'
        ? `${t('faqBubbleBody')}\n\n${tip}`
        : faq === 'clipboard'
          ? t(Platform.OS === 'ios' ? 'faqClipboardBodyIos' : 'faqClipboardBody')
        : faq
          ? t(`faq${faq[0].toUpperCase()}${faq.slice(1)}Body` as 'faqClipboardBody' | 'faqSaveBody' | 'faqEditorBody' | 'faqModelBody')
          : '';
  const title = sheet === 'flow'
    ? t('howItWorks')
    : sheet === 'licenses'
      ? t('licenses')
      : faq === 'bubble'
        ? t('faqBubbleTitle')
        : faq === 'clipboard'
          ? t('faqClipboardTitle')
          : faq === 'save'
            ? t('faqSaveTitle')
            : faq === 'editor'
              ? t('faqEditorTitle')
              : faq === 'model'
                ? t('faqModelTitle')
                : '';

  return (
    <Screen scroll>
      <BackButton onPress={onBack} />
      <Text className="mt-2 text-3xl font-semibold" style={{ color: colors.text }}>{t('settings')}</Text>
      <Notice message={notice} />
      <Row label={t('language')} onPress={onLanguage} colors={colors} />
      <Row label={t('howItWorks')} onPress={() => setSheet('flow')} colors={colors} />
      <Row label={t('licenses')} onPress={() => setSheet('licenses')} colors={colors} />
      <Text className="mb-1 mt-8 text-sm font-medium" style={{ color: colors.textMuted }}>{t('theme')}</Text>
      <View className="flex-row gap-2">
        {(['system', 'light', 'dark'] as ThemeMode[]).map(mode => {
          const selected = mode === themeMode;
          const label = mode === 'system' ? t('themeSystem') : mode === 'light' ? t('themeLight') : t('themeDark');
          return <Pressable key={mode} accessibilityRole="button" accessibilityState={{ selected }} className="min-h-12 flex-1 items-center justify-center rounded-2xl border px-2" style={{ minHeight: 48, backgroundColor: selected ? colors.primary : colors.surface, borderColor: selected ? colors.primary : colors.border }} onPress={() => onThemeModeChange?.(mode)}><Text className="text-sm font-semibold" style={{ color: selected ? colors.onPrimary : colors.text }}>{label}</Text></Pressable>;
        })}
      </View>
      {onAnalyticsChange || onCrashChange ? <Text className="mb-1 mt-8 text-sm font-medium" style={{ color: colors.textMuted }}>{t('privacy')}</Text> : null}
      {onAnalyticsChange ? <ConsentRow label={t('analyticsConsent')} value={analyticsEnabled === true} onChange={onAnalyticsChange} colors={colors} /> : null}
      {onCrashChange ? <ConsentRow label={t('crashConsent')} value={crashEnabled === true} onChange={onCrashChange} colors={colors} /> : null}
      {onOpenPrivacy ? <Row label={t('privacyPolicy')} onPress={onOpenPrivacy} colors={colors} /> : null}
      {onFeedback ? <Row label={t('feedback')} onPress={onFeedback} colors={colors} /> : null}
      <Text className="mb-1 mt-8 text-sm font-medium" style={{ color: colors.textMuted }}>{t('faqHeading')}</Text>
      {Platform.OS === 'android' ? <Row label={t('faqBubbleTitle')} onPress={() => setSheet('bubble')} colors={colors} /> : null}
      <Row label={t('faqClipboardTitle')} onPress={() => setSheet('clipboard')} colors={colors} />
      <Row label={t('faqSaveTitle')} onPress={() => setSheet('save')} colors={colors} />
      <Row label={t('faqEditorTitle')} onPress={() => setSheet('editor')} colors={colors} />
      <Row label={t('faqModelTitle')} onPress={() => setSheet('model')} colors={colors} />
      <Sheet title={title} body={body} visible={sheet != null} closeLabel={t('gotIt')} onClose={() => setSheet(null)} />
    </Screen>
  );
}

function Row({ label, onPress, colors }: { label: string; onPress: () => void; colors: ReturnType<typeof useTheme>['colors'] }) {
  return (
    <Pressable accessibilityRole="button" className="mt-3 min-h-14 justify-center rounded-3xl border px-4 py-3" style={{ minHeight: 56, backgroundColor: colors.surface, borderColor: colors.border }} onPress={onPress}>
      <Text className="text-base font-semibold" style={{ color: colors.text }}>{label}</Text>
    </Pressable>
  );
}

function ConsentRow({ label, value, onChange, colors }: { label: string; value: boolean; onChange: (value: boolean) => void; colors: ReturnType<typeof useTheme>['colors'] }) {
  return <Pressable accessibilityRole="switch" accessibilityState={{ checked: value }} accessibilityLabel={label} className="mt-3 min-h-14 flex-row items-center justify-between rounded-3xl border px-4 py-3" style={{ minHeight: 56, backgroundColor: colors.surface, borderColor: colors.border }} onPress={() => onChange(!value)}><Text className="flex-1 pr-4 text-base font-semibold" style={{ color: colors.text }}>{label}</Text><View className="h-7 w-12 justify-center rounded-full px-1" style={{ backgroundColor: value ? colors.primary : colors.surfaceRaised }}><View className="h-5 w-5 rounded-full" style={{ alignSelf: value ? 'flex-end' : 'flex-start', backgroundColor: colors.onPrimary }} /></View></Pressable>;
}
