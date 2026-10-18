import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, ActivityIndicator, DeviceEventEmitter, Image, Pressable, ScrollView, Text, ToastAndroid, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { SweepPreview } from '../components/SweepPreview';
import { Notice } from '../components/ui/notice';
import { bubbleFrame } from '../logic/bubble';
import { canUseClipboard } from '../logic/clipboard';
import { clampPercent } from '../logic/progress';
import { savePhase } from '../logic/save';
import { isRtl } from '../logic/languages';
import { localizeMessage } from '../i18n/catalogs';
import { useLocale } from '../i18n/LocaleGate';
import { Erase, type BubbleGeometry, type BubbleSnapshot } from '../native/Erase';
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
  const [geometry, setGeometry] = useState<BubbleGeometry | null>(null);
  const geometryRevision = useRef(-1);
  const [note, setNote] = useState('');
  const [action, setAction] = useState<'copy' | 'save' | 'openEditor' | null>(null);
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

  useEffect(() => {
    let live = true;
    const apply = (next: BubbleGeometry) => {
      if (!live || next.revision <= geometryRevision.current) return;
      geometryRevision.current = next.revision;
      setGeometry(next);
    };
    const subscription = DeviceEventEmitter.addListener('bubbleGeometry', apply);
    void Erase.getBubbleGeometry?.().then(next => { if (next) apply(next); }).catch(() => undefined);
    return () => { live = false; subscription.remove(); };
  }, []);
  useEffect(() => {
    if (geometry) Erase.ackBubbleGeometry?.(geometry.revision);
  }, [geometry]);

  const busy = state.phase === 'working';
  const result = state.phase === 'copied' || (state.phase === 'failed' && state.editReady);
  const anchorEnd = geometry?.anchorEnd ?? state.anchorEnd;
  const chipLeft = geometry?.chipLeftDp ?? 272;
  const chipTop = geometry?.chipTopDp ?? 0;
  const panelWidth = geometry?.panelWidthDp ?? 260;
  const panelHeight = geometry?.panelHeightDp ?? bubbleFrame({ ...state, expanded: true }).height;
  useEffect(() => {
    side.value = anchorEnd ? 1 : -1;
    panel.value = state.expanded && !reducedMotion ? withTiming(1, { duration: 160 }) : state.expanded ? 1 : 0;
  }, [anchorEnd, panel, reducedMotion, side, state.expanded]);

  const panelStyle = useAnimatedStyle(() => ({
    opacity: panel.value,
    transform: [{ translateX: (1 - panel.value) * 12 * side.value }],
  }));

  async function run(action: 'toggle' | 'clipboard' | 'gallery' | 'file' | 'refine' | 'dismiss' | 'again') {
    if (actionRef.current || savingRef.current) return;
    actionRef.current = true;
    if (action === 'refine') setAction('openEditor');
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
      if (action === 'refine' && !next.expanded) ToastAndroid.show(t('editInApp'), ToastAndroid.SHORT);
    } catch {
      setNote(t('readFailed'));
    } finally {
      actionRef.current = false;
      setAction(null);
    }
  }

  async function hide() {
    await Erase.hideBubble();
  }

  async function collapse(message: string) {
    // Collapse is idempotent; a delayed success can never reopen the panel.
    const next = await Erase.bubbleAction('collapse');
    const revision = next.revision ?? revisionRef.current + 1;
    if (revision >= revisionRef.current) {
      revisionRef.current = revision;
      setState(next);
    }
    ToastAndroid.show(message, ToastAndroid.SHORT);
    AccessibilityInfo.announceForAccessibility(message);
    setNote('');
  }

  async function copy() {
    if (!state.previewPath || savingRef.current || actionRef.current) return;
    savingRef.current = true;
    setAction('copy');
    try {
      const status = await Erase.publishPng(state.previewPath);
      if (status === 'copied') await collapse(t('copied'));
      else setNote(t('nothingToCopy'));
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setAction(null);
    }
  }

  async function save() {
    if (!state.previewPath || savingRef.current || actionRef.current) return;
    const path = state.previewPath as string;
    savingRef.current = true;
    setAction('save');
    try {
      const status = await Erase.saveToGallery(path);
      const message = t(savePhase(status) === 'saved' ? 'saved' : 'saveFailed');
      if (savePhase(status) === 'saved') await collapse(message);
      else setNote(message);
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setAction(null);
    }
  }

  const actions = (
    <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 12 }} className="flex-1 rounded-3xl" testID="bubble-actions" style={{ direction: isRtl(locale) ? 'rtl' : 'ltr', backgroundColor: `${colors.surface}E6`, borderColor: colors.border, borderWidth: 1 }}>
      <Pressable accessibilityRole="button" accessibilityLabel={t('hideBubble')} onPress={() => { void hide(); }} style={{ minHeight: 48, alignSelf: 'flex-end', justifyContent: 'center', paddingHorizontal: 16 }}><Text style={{ color: colors.text }}>✕</Text></Pressable>
      <Text className="mb-2 text-base font-semibold" style={{ color: colors.text }}>{busy ? t('cutting') : t('cutBackground')}</Text>
      {busy ? (
        <View>
          <SweepPreview overlayVisible path={state.previewPath} width={220} height={112} />
          {state.stage === 'inference' ? <ActivityIndicator className="mt-3" color={colors.accent} /> : null}
          {state.stage === 'inference' ? <Text className="mt-3 text-center text-base" style={{ color: colors.textMuted }}>{t('processingInference')}</Text> : null}
          <Text accessibilityRole="progressbar" accessibilityLabel={t('processing')} accessibilityValue={{ min: 0, max: 100, now: clampPercent(state.percent) }} className="mt-3 text-center text-lg font-semibold" style={{ color: colors.text }}>{clampPercent(state.percent)}%</Text>
        </View>
      ) : null}
      {result ? (
        <View>
          {state.previewPath ? (
            <BubblePreview path={state.previewPath} background={colors.surfaceRaised} label={t('done')} />
          ) : null}
          <Notice message={note} />
          <Action label={t('copy')} loading={action === 'copy'} disabled={action != null || !state.previewPath} onPress={() => { void copy(); }} colors={colors} />
          <Action label={t('save')} loading={action === 'save'} disabled={action != null || !state.previewPath} onPress={() => { void save(); }} colors={colors} />
          {state.editReady ? <Action label={t('editInApp')} loading={action === 'openEditor'} disabled={action != null} onPress={() => { void run('refine'); }} colors={colors} /> : null}
          <Action label={t('another')} disabled={action != null} onPress={() => { void run('again'); }} colors={colors} />
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
    <View testID="bubble-surface" style={{ width: 628, height: 400, direction: 'ltr' }}>
      <View testID="bubble-chip" style={{ direction: 'ltr', position: 'absolute', left: chipLeft, top: chipTop, width: 84, height: 84 }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('cutBackground')}
          className="items-center justify-center overflow-hidden rounded-full"
          style={{ width: 76, height: 76, marginTop: 4, marginLeft: 4, backgroundColor: colors.primary, opacity: 0.7 }}
          onPress={() => { void run('toggle'); }}>
          <Image source={require('../assets/bubble-icon.png')} style={{ width: 48, height: 48, tintColor: colors.onPrimary }} resizeMode="contain" />
        </Pressable>

      </View>
      {state.expanded ? (
        <Animated.View testID="bubble-panel" style={[{ direction: 'ltr', position: 'absolute', left: anchorEnd ? 260 - panelWidth : 368, top: 0, width: panelWidth, height: panelHeight }, panelStyle]}>
          {actions}
        </Animated.View>
      ) : null}
    </View>
  );
}

function Action({ label, onPress, disabled = false, loading = false, colors }: { label: string; onPress: () => void; disabled?: boolean; loading?: boolean; colors: ReturnType<typeof useTheme>['colors'] }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled, busy: loading }}
      disabled={disabled}
      className="mb-2 min-h-12 flex-row gap-2 items-center justify-center rounded-2xl px-2 py-2"
      style={{ minHeight: 48, backgroundColor: colors.surfaceRaised, opacity: disabled ? 0.5 : 1, borderColor: colors.border, borderWidth: 1 }}
      onPress={onPress}>
      {loading ? <ActivityIndicator size="small" color={colors.text} /> : null}
      <Text className="text-sm font-semibold" style={{ color: colors.text }}>{label}</Text>
    </Pressable>
  );
}

const BubblePreview = memo(function BubblePreview({ path, background, label }: { path: string; background: string; label: string }) {
  const source = useMemo(() => ({ uri: `file://${path}` }), [path]);
  return <Image accessibilityLabel={label} source={source} style={{ height: 96, width: '100%', backgroundColor: background }} className="mb-2 rounded-xl" resizeMode="contain" />;
});
