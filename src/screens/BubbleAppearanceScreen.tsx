import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, Switch, Text, View } from 'react-native';
import { BubbleGlyph } from '../components/BubbleGlyph';
import { BackButton } from '../components/ui/backButton';
import { AppearanceSlider } from '../components/ui/appearanceSlider';
import { Notice } from '../components/ui/notice';
import { Screen } from '../components/ui/screen';
import { DEFAULT_BUBBLE_APPEARANCE, normalizeBubbleAppearance, readBubbleAppearance, writeBubbleAppearance } from '../logic/bubbleAppearance';
import { type BubbleAppearance } from '../native/Erase';
import { useTheme } from '../theme/ThemeProvider';
import { useLocale } from '../i18n/LocaleGate';

export function BubbleAppearanceScreen({ mode, onBack }: { mode: 'size' | 'opacity'; onBack: () => void }) {
  const { colors } = useTheme();
  const { t } = useLocale();
  const [value, setValue] = useState(DEFAULT_BUBBLE_APPEARANCE);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [compact, setCompact] = useState(false);
  const [previewActivity, setPreviewActivity] = useState(0);
  const current = useRef(value);
  const mounted = useRef(true);
  const edit = (patch: Partial<BubbleAppearance>) => {
    const next = normalizeBubbleAppearance({ ...current.current, ...patch });
    current.current = next; setValue(next);
    return next;
  };
  const save = (patch: Partial<BubbleAppearance>) => {
    const next = edit(patch);
    setError('');
    void writeBubbleAppearance(next).catch(() => {
      if (mounted.current) setError(t('saveFailed'));
    });
  };
  const load = () => {
    setError('');
    void readBubbleAppearance().then(saved => {
      if (mounted.current) { edit(saved); setLoaded(true); }
    }).catch(() => { if (mounted.current) setError(t('appearanceLoadFailed')); });
  };
  useEffect(() => { mounted.current = true; load(); return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    setCompact(false);
    if (!value.shrinkWhenIdle || mode !== 'size') return;
    const timer = setTimeout(() => setCompact(true), 3000);
    return () => clearTimeout(timer);
  }, [value, mode, previewActivity]);
  const opacity = mode === 'opacity';
  const title = t(opacity ? 'bubbleOpacity' : 'bubbleSize');
  return <Screen scroll>
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
      <BackButton onPress={onBack} />
      <Text accessibilityRole="header" style={{ flex: 1, fontSize: 20, fontWeight: '600', color: colors.text }}>{title}</Text>
      <Pressable accessibilityRole="button" disabled={!loaded} onPress={() => save(opacity ? { opacity: 0.7 } : { sizeDp: 64, shrinkWhenIdle: false, shrinkToDot: false })}
        style={{ minHeight: 48, justifyContent: 'center', paddingHorizontal: 8, opacity: loaded ? 1 : 0.4 }}>
        <Text style={{ color: colors.primary, fontWeight: '600' }}>{t('reset')}</Text>
      </Pressable>
    </View>
    <Notice message={error} />
    {!loaded ? <View style={{ minHeight: 240, alignItems: 'center', justifyContent: 'center' }}>
      {error ? <Pressable accessibilityRole="button" onPress={load} style={{ minHeight: 48, justifyContent: 'center' }}><Text style={{ color: colors.primary }}>{t('retry')}</Text></Pressable> : <ActivityIndicator color={colors.primary} />}
    </View> : <>
      <Pressable accessibilityRole="button" accessibilityLabel={t('bubblePreview')} onPress={() => { setCompact(false); setPreviewActivity(previous => previous + 1); }}
        style={{ minHeight: 260, alignItems: 'center', justifyContent: 'center', marginTop: 20 }}>
        <View style={{ position: 'absolute', width: 180, height: 180, borderRadius: 90, backgroundColor: colors.surfaceRaised }} />
        <BubbleGlyph size={value.sizeDp} opacity={value.opacity} compact={compact} dot={value.shrinkToDot} />
      </Pressable>
      <Text style={{ color: colors.text, textAlign: 'center', fontSize: 26, fontFamily: 'Manrope', fontWeight: '600', marginBottom: 32 }}>{opacity ? `${Math.round(value.opacity * 100)}%` : String(value.sizeDp)}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
        <Text style={{ color: colors.textMuted, fontSize: opacity ? 16 : 14 }}>{opacity ? '20%' : 'A'}</Text>
        <View style={{ flex: 1 }}><AppearanceSlider label={title} value={opacity ? value.opacity : value.sizeDp}
          min={opacity ? 0.2 : 48} max={opacity ? 1 : 88} step={opacity ? 0.05 : 4}
          format={n => opacity ? `${Math.round(n * 100)}%` : String(n)}
          onChange={n => edit(opacity ? { opacity: n } : { sizeDp: n })}
          onCommit={n => save(opacity ? { opacity: n } : { sizeDp: n })} /></View>
        <Text style={{ color: colors.textMuted, fontSize: opacity ? 16 : 26 }}>{opacity ? '100%' : 'A'}</Text>
      </View>
      {!opacity ? <View style={{ marginTop: 40, gap: 20 }}>
        <Toggle label={t('bubbleShrinkIdle')} value={value.shrinkWhenIdle} onChange={enabled => save({ shrinkWhenIdle: enabled })} />
        <Toggle label={t('bubbleShrinkDot')} value={value.shrinkToDot} disabled={!value.shrinkWhenIdle} onChange={enabled => save({ shrinkToDot: enabled })} />
        <Text style={{ color: colors.textMuted, fontSize: 14, lineHeight: 21 }}>{t('bubbleIdleHint')}</Text>
      </View> : null}
    </>}
  </Screen>;
}

function Toggle({ label, value, disabled, onChange }: { label: string; value: boolean; disabled?: boolean; onChange: (value: boolean) => void }) {
  const { colors } = useTheme();
  return <View style={{ minHeight: 56, flexDirection: 'row', alignItems: 'center', opacity: disabled ? 0.45 : 1 }}>
    <Text style={{ flex: 1, color: colors.text, fontSize: 17, paddingEnd: 16 }}>{label}</Text>
    <Switch accessible accessibilityLabel={label} value={value} disabled={disabled} onValueChange={onChange} trackColor={{ false: colors.border, true: colors.primary }} thumbColor="#FFFFFF" />
  </View>;
}
