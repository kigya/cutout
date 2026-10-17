import { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ActivityIndicator, DeviceEventEmitter, Image, Pressable, ScrollView, Text, View } from 'react-native';
import Animated, { FadeIn, useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { SweepPreview } from '../components/SweepPreview';
import { Notice } from '../components/ui/notice';
import { bubbleFrame } from '../logic/bubble';
import { canUseClipboard } from '../logic/clipboard';
import { clampPercent } from '../logic/progress';
import { savePhase } from '../logic/save';
import { localizeMessage } from '../i18n/catalogs';
import { useLocale } from '../i18n/LocaleGate';
import { Erase, type BubbleSnapshot } from '../native/Erase';
import { useTheme } from '../theme/ThemeProvider';

const EMPTY: BubbleSnapshot = {
  expanded: false,
  anchorEnd: true,
  phase: 'idle',
  clipboardReady: false,
  readingClipboard: false,
  message: null,
  previewPath: null,
  rasterizedSvg: false,
  editReady: false,
  percent: 0,
};

export function BubbleApp() {
  const { t, locale } = useLocale();
  const { colors } = useTheme();
  const localeRef = useRef(locale);
  localeRef.current = locale;
  const [state, setState] = useState<BubbleSnapshot>(EMPTY);
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(false);
  const savingRef = useRef(false);
  const actionRef = useRef(false);
  const revisionRef = useRef(-1);
  const panel = useSharedValue(0);
  const side = useSharedValue(1);

  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReducedMotion(value); });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    return () => { mounted = false; motion.remove(); };
  }, []);

  useEffect(() => {
    let live = true;
    const applySnapshot = (snapshot: BubbleSnapshot & { revision?: number }) => {
      if (!live) return;
      const revision = snapshot.revision ?? revisionRef.current + 1;
      if (revision < revisionRef.current) return;
      revisionRef.current = revision;
      setState(snapshot);
    };
    const frames = DeviceEventEmitter.addListener('bubbleState', event => {
      applySnapshot(event as BubbleSnapshot & { revision?: number });
    });
    void Erase.getBubbleState().then(snapshot => {
      if (live && revisionRef.current < 0) applySnapshot(snapshot as BubbleSnapshot & { revision?: number });
    }).catch(() => undefined);
    return () => {
      live = false;
      frames.remove();
    };
  }, []);

  const busy = state.phase === 'working';
  const result = state.phase === 'copied' || (state.phase === 'failed' && state.editReady);
  const frame = bubbleFrame(state);
  useEffect(() => {
    Erase.setBubbleSize(frame.width, frame.height);
    side.value = state.anchorEnd ? 1 : -1;
    panel.value = state.expanded && !reducedMotion ? withTiming(1, { duration: 160 }) : state.expanded ? 1 : 0;
  }, [frame.height, frame.width, panel, reducedMotion, side, state.anchorEnd, state.expanded]);

  const panelStyle = useAnimatedStyle(() => ({
    opacity: panel.value,
    transform: [{ translateX: (1 - panel.value) * 12 * side.value }],
  }));

  async function run(action: 'toggle' | 'clipboard' | 'gallery' | 'file' | 'refine' | 'dismiss' | 'again') {
    if (actionRef.current) return;
    actionRef.current = true;
    if (action === 'clipboard' || action === 'gallery' || action === 'file') {
      setNote('');
    }
    try {
      const next = await Erase.bubbleAction(action);
      const revision = (next as BubbleSnapshot & { revision?: number }).revision ?? revisionRef.current + 1;
      if (revision >= revisionRef.current) {
        revisionRef.current = revision;
        setState(next);
      }
      setNote(localizeMessage(localeRef.current, next.message));
    } catch {
      setNote(t('readFailed'));
    } finally {
      actionRef.current = false;
    }
  }

  async function hide() {
    await Erase.hideBubble();
  }

  async function copy() {
    if (!state.previewPath || savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    setNote(t('copying'));
    try {
      const status = await Erase.publishPng(state.previewPath);
      setNote(status === 'copied' ? t('copied') : t('nothingToCopy'));
      AccessibilityInfo.announceForAccessibility(status === 'copied' ? t('copied') : t('nothingToCopy'));
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  async function save() {
    if (savingRef.current) return;
    const path = state.previewPath as string;
    savingRef.current = true;
    setSaving(true);
    setNote(t('saving'));
    try {
      const status = await Erase.saveToGallery(path);
      const message = t(savePhase(status) === 'saved' ? 'saved' : 'saveFailed');
      setNote(message);
      AccessibilityInfo.announceForAccessibility(message);
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  const actions = (
    <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 12 }} className="flex-1 rounded-3xl" style={{ backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1 }}>
      <Text className="mb-2 text-base font-semibold" style={{ color: colors.text }}>{busy ? t('cutting') : t('cutBackground')}</Text>
      {busy ? (
        <View>
          <SweepPreview overlayVisible path={state.previewPath} width={220} height={112} />
          {state.stage === 'inference' ? <ActivityIndicator className="mt-3" color={colors.accent} /> : null}
          <Text className="mt-3 text-center text-lg font-semibold" style={{ color: colors.text }}>{state.stage === 'inference' ? t('processingInference') : `${clampPercent(state.percent)}%`}</Text>
        </View>
      ) : null}
      {result ? (
        <View>
          {state.previewPath ? (
            <Image source={{ uri: `file://${state.previewPath}` }} style={{ height: 96, width: '100%', backgroundColor: colors.surfaceRaised }} className="mb-2 rounded-xl" resizeMode="contain" />
          ) : null}
          <Notice message={note} />
          <Action label={saving ? t('copying') : t('copy')} disabled={saving || !state.previewPath} onPress={() => { void copy(); }} colors={colors} />
          <Action label={saving ? t('saving') : t('save')} disabled={saving || !state.previewPath} onPress={() => { void save(); }} colors={colors} />
          {state.editReady ? <Action label={t('editInApp')} disabled={saving} onPress={() => { void run('refine'); }} colors={colors} /> : null}
          <Action label={t('another')} disabled={saving} onPress={() => { void run('again'); }} colors={colors} />
        </View>
      ) : null}
      {!busy && !result ? (
        <View>
          <Action label={t('fromClipboard')} disabled={!canUseClipboard(state.clipboardReady)} onPress={() => { void run('clipboard'); }} colors={colors} />
          <Action label={t('fromGallery')} onPress={() => { void run('gallery'); }} colors={colors} />
          <Action label={t('file')} onPress={() => { void run('file'); }} colors={colors} />
          <Notice message={note} />
        </View>
      ) : null}
    </ScrollView>
  );

  return (
    <View style={{ width: frame.width, height: frame.height, flexDirection: state.anchorEnd ? 'row-reverse' : 'row' }}>
      <View style={{ width: 84, height: 84 }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('cutBackground')}
          className="items-center justify-center overflow-hidden rounded-full"
          style={{ width: 76, height: 76, marginTop: 4, marginLeft: 4, backgroundColor: colors.primary }}
          onPress={() => { void run('toggle'); }}>
          <Image source={require('../assets/bubble-icon.png')} style={{ width: 48, height: 48, tintColor: colors.onPrimary }} resizeMode="contain" />
        </Pressable>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('hideBubble')}
          className="absolute right-0 top-0 h-12 w-12 items-center justify-center"
          onPress={() => { void hide(); }}>
          <View className="h-12 w-12 items-center justify-center rounded-full" style={{ backgroundColor: colors.surfaceRaised, borderColor: colors.border, borderWidth: 1 }}>
            <Text className="text-xs" style={{ color: colors.text }}>✕</Text>
          </View>
        </Pressable>
      </View>
      {state.expanded ? <View style={{ width: 12 }} /> : null}
      {state.expanded ? (
        <Animated.View entering={reducedMotion ? undefined : FadeIn.duration(160)} style={[{ flex: 1 }, panelStyle]}>
          {actions}
        </Animated.View>
      ) : null}
    </View>
  );
}

function Action({ label, onPress, disabled = false, colors }: { label: string; onPress: () => void; disabled?: boolean; colors: ReturnType<typeof useTheme>['colors'] }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      className="mb-2 min-h-12 items-center justify-center rounded-2xl px-2 py-2"
      style={{ minHeight: 48, backgroundColor: colors.surfaceRaised, opacity: disabled ? 0.5 : 1, borderColor: colors.border, borderWidth: 1 }}
      onPress={onPress}>
      <Text className="text-sm font-semibold" style={{ color: colors.text }}>{label}</Text>
    </Pressable>
  );
}
