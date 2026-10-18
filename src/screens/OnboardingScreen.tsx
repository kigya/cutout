import { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, BackHandler, I18nManager, Image, PanResponder, Platform, Pressable, ScrollView, Text, useWindowDimensions, View } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';
import { BubbleScene, ClipboardScene, PhotoScene } from '../onboarding/Scenes';
import { useLocale } from '../i18n/LocaleGate';
import { useTheme } from '../theme/ThemeProvider';
import { SYSTEM_BAR_GAP, useSystemBars } from '../logic/systemBars';
import { Notice } from '../components/ui/notice';

export function OnboardingScreen({ onComplete }: { onComplete: () => Promise<void> }) {
  const { t, locale } = useLocale();
  const { colors, isDark } = useTheme();
  const insets = useSystemBars();
  const { width, height } = useWindowDimensions();
  const [page, setPage] = useState(0);
  const pageRef = useRef(page); pageRef.current = page;
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [error, setError] = useState('');
  const wide = width >= 760 && width > height;
  const usableWidth = Math.max(200, width - insets.left - insets.right - 48);
  const frameWidth = wide ? Math.min(usableWidth * 0.46, 480) : Math.min(usableWidth, 430);
  const scale = Math.max(0.4, Math.min(frameWidth / 340, (wide ? height - insets.top - insets.bottom - 140 : height < 740 ? 290 : 410) / 396));
  const typography = ['en', 'ru', 'uk', 'de', 'es', 'fr', 'it', 'pt', 'nl', 'pl', 'cs', 'ro', 'sv', 'tr', 'vi', 'id', 'ms', 'fil'].includes(locale) ? 'Manrope' : undefined;
  const responder = useMemo(() => PanResponder.create({
    onMoveShouldSetPanResponder: (_, gesture) => Math.abs(gesture.dx) > 30 && Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.5,
    onPanResponderRelease: (_, gesture) => {
      if (Math.abs(gesture.dx) < 45) return;
      const direction = gesture.dx * (I18nManager.isRTL ? -1 : 1) < 0 ? 1 : -1;
      setPage(Math.max(0, Math.min(2, pageRef.current + direction)));
    },
  }), []);
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (pageRef.current === 0) return false;
      setPage(previous => previous - 1); return true;
    });
    return () => subscription.remove();
  }, []);
  const title = page === 0 ? t('onboardingTitlePhoto') : page === 1 ? t('onboardingTitleClipboard') : t(Platform.OS === 'ios' ? 'onboardingTitleIos' : 'onboardingTitleBubble');
  const body = page === 0 ? t('onboardingBodyPhoto') : page === 1 ? t('onboardingBodyClipboard') : t(Platform.OS === 'ios' ? 'onboardingBodyIos' : 'onboardingBodyBubble');
  useEffect(() => {
    AccessibilityInfo.announceForAccessibility(`${page + 1} / 3. ${title}`);
  }, [page, title]);
  const finish = async () => {
    if (savingRef.current) return;
    if (page < 2) { setPage(page + 1); return; }
    savingRef.current = true; setSaving(true); setError('');
    try { await onComplete(); } catch { savingRef.current = false; setError(t('saveFailed')); setSaving(false); }
  };
  const copy = <View style={{ width: wide ? '44%' : '100%', paddingHorizontal: wide ? 24 : 0 }}>
    <Text accessibilityRole="header" style={{ fontFamily: typography, fontSize: width < 360 ? 31 : 38, lineHeight: width < 360 ? 38 : 46, fontWeight: '700', letterSpacing: typography ? -1.3 : 0, color: colors.text, textAlign: wide ? 'auto' : 'center' }}>{title}</Text>
    <Text style={{ fontSize: 16, lineHeight: 24, marginTop: 14, color: colors.textMuted, textAlign: wide ? 'auto' : 'center' }}>{body}</Text>
  </View>;
  return <View style={{ flex: 1, backgroundColor: colors.background, paddingTop: insets.top + SYSTEM_BAR_GAP, paddingBottom: insets.bottom + SYSTEM_BAR_GAP, paddingLeft: insets.left, paddingRight: insets.right }} {...responder.panHandlers}>
    <View style={{ paddingHorizontal: 24, flexDirection: 'row', alignItems: 'center', minHeight: 64, width: '100%', maxWidth: 1080, alignSelf: 'center' }}>
      <View style={{ width: 36, height: 36, backgroundColor: colors.primary, borderRadius: 11, alignItems: 'center', justifyContent: 'center' }}><Image source={require('../assets/bubble-icon.png')} style={{ width: 26, height: 26, tintColor: '#FFFFFF' }} /></View>
      <Text allowFontScaling={false} style={{ fontFamily: 'Manrope', fontWeight: '700', fontSize: 24, letterSpacing: -0.8, color: colors.text, marginStart: 10 }}>Cutout</Text>
      <Text accessible={false} style={{ marginStart: 'auto', fontSize: 12, letterSpacing: 2, color: colors.textMuted }}>{`0${page + 1} / 03`}</Text>
    </View>
    <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', paddingHorizontal: 24, paddingVertical: 12 }}>
      <Animated.View key={page} entering={FadeIn.duration(220)} style={{ width: '100%', maxWidth: 1000, alignSelf: 'center', alignItems: 'center', flexDirection: wide ? 'row' : 'column', gap: wide ? 20 : 8 }}>
        {wide ? copy : null}
        <View style={{ width: frameWidth, height: 396 * scale, alignItems: 'center', justifyContent: 'center', marginBottom: wide ? 0 : 16 }}>
          <View style={{ width: 340, height: 396, direction: 'ltr', transform: [{ scale }] }}>{page === 0 ? <PhotoScene /> : page === 1 ? <ClipboardScene /> : <BubbleScene />}</View>
        </View>
        {!wide ? copy : null}
      </Animated.View>
    </ScrollView>
    <View style={{ width: '100%', maxWidth: 520, alignSelf: 'center', paddingHorizontal: 24, paddingTop: 6 }}>
      <Notice message={error} />
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', marginBottom: 10 }}>
        {[0, 1, 2].map(index => <Pressable key={index} accessibilityRole="button" accessibilityLabel={`${t('onboardingPage')} ${index + 1} / 3`} accessibilityState={{ selected: page === index }} onPress={() => setPage(index)} style={{ width: 48, height: 48, alignItems: 'center', justifyContent: 'center' }}>
          <View style={{ width: page === index ? 26 : 7, height: 7, borderRadius: 4, backgroundColor: page === index ? colors.primary : colors.border }} />
        </Pressable>)}
      </View>
      <Pressable accessibilityRole="button" accessibilityState={{ disabled: saving, busy: saving }} disabled={saving} onPress={() => { void finish(); }} style={({ pressed }) => ({ minHeight: 58, borderRadius: 20, backgroundColor: colors.primary, alignItems: 'center', justifyContent: 'center', opacity: pressed || saving ? 0.8 : 1, boxShadow: isDark ? undefined : '0 7px 16px rgba(34,89,207,0.2)' })}>
        <Text style={{ fontFamily: typography, fontWeight: '700', fontSize: 17, color: colors.onPrimary }}>{t(page === 2 ? 'onboardingGetStarted' : 'onboardingNext')}</Text>
      </Pressable>
    </View>
  </View>;
}
