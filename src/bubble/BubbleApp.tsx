import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, ActivityIndicator, DeviceEventEmitter, Image, Pressable, ScrollView, Text, ToastAndroid, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { BubbleGlyph } from '../components/BubbleGlyph';
import { DEFAULT_BUBBLE_APPEARANCE, normalizeBubbleAppearance } from '../logic/bubbleAppearance';
import { SweepPreview } from '../components/SweepPreview';
import { Notice } from '../components/ui/notice';
import { bubbleFrame } from '../logic/bubble';
import { useDisplayedProgress } from '../logic/useDisplayedProgress';
import { savePhase } from '../logic/save';
import { isRtl } from '../logic/languages';
import { localizeMessage } from '../i18n/catalogs';
import { useLocale } from '../i18n/LocaleGate';
import { Erase, type BubbleGeometry, type BubbleSnapshot, type BubbleAppearance } from '../native/Erase';
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
  const [appearance, setAppearance] = useState(DEFAULT_BUBBLE_APPEARANCE);
  const [compact, setCompact] = useState(false);
  const [interacting, setInteracting] = useState(false);
  const [activity, setActivity] = useState(0);
  const [note, setNote] = useState('');
  const [action, setAction] = useState<'copy' | 'save' | 'openEditor' | null>(null);
  const [reducedMotion, setReducedMotion] = useState(false);
  const savingRef = useRef(false);
  const actionRef = useRef(false);
  const revisionRef = useRef(-1);
  const mountedRef = useRef(true);
  const panel = useSharedValue(0);
  const side = useSharedValue(1);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

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

  useEffect(() => {
    let live = true;
    let received = false;
    const appearanceEvent = DeviceEventEmitter.addListener('bubbleAppearance', (next: BubbleAppearance) => {
      received = true; setAppearance(normalizeBubbleAppearance(next));
    });
    const interactionEvent = DeviceEventEmitter.addListener('bubbleInteraction', (event: { active: boolean }) => {
      setInteracting(event.active); setCompact(false); setActivity(previous => previous + 1);
    });
    void Erase.getBubbleAppearance().then(next => { if (live && !received) setAppearance(normalizeBubbleAppearance(next)); }).catch(() => undefined);
    return () => { live = false; appearanceEvent.remove(); interactionEvent.remove(); };
  }, []);
  useEffect(() => {
    setCompact(false);
    if (!appearance.shrinkWhenIdle || state.expanded || state.phase === 'working' || state.readingClipboard || action || interacting) return;
    const timer = setTimeout(() => setCompact(true), 3000);
    return () => clearTimeout(timer);
  }, [appearance, state.expanded, state.phase, state.readingClipboard, action, interacting, activity]);

  const chipSize = geometry?.chipSizeDp ?? appearance.sizeDp + 8;
  const visualSize = chipSize - 8;
  const busy = state.phase === 'working';
  const displayedPercent = useDisplayedProgress(state.percent, busy, state.taskId);
  const result = state.phase === 'copied' || (state.phase === 'failed' && state.editReady);
  const anchorEnd = geometry?.anchorEnd ?? state.anchorEnd;
  const chipLeft = geometry?.chipLeftDp ?? 272;
  const chipTop = geometry?.chipTopDp ?? 0;
  const panelWidth = geometry?.panelWidthDp ?? 260;
  const panelHeight = geometry?.panelHeightDp ?? bubbleFrame({ ...state, expanded: true }).height;
  const expanded = state.expanded && (!geometry || (geometry.expanded &&
    (state.revision ?? 0) >= (geometry.stateRevision ?? 0) && (!geometry.phase || geometry.phase === state.phase)));
  const notice = note || localizeMessage(locale, state.message);
  useEffect(() => {
    side.value = anchorEnd ? 1 : -1;
    panel.value = state.expanded && !reducedMotion ? withTiming(1, { duration: 160 }) : state.expanded ? 1 : 0;
  }, [anchorEnd, panel, reducedMotion, side, state.expanded]);

  const panelStyle = useAnimatedStyle(() => ({
    opacity: panel.value,
    transform: [{ translateX: (1 - panel.value) * 12 * side.value }],
  }));

  async function run(action: 'toggle' | 'clipboard' | 'gallery' | 'file' | 'refine' | 'dismiss' | 'again') {
    setCompact(false); setActivity(previous => previous + 1);
    const control = action === 'toggle' || action === 'dismiss';
    if (!control && (actionRef.current || savingRef.current)) return;
    if (!control) actionRef.current = true;
    if (action === 'refine') setAction('openEditor');
    if (action === 'clipboard' || action === 'gallery' || action === 'file') {
      setNote('');
    }
    try {
      const next = await Erase.bubbleAction(action);
      if (!mountedRef.current) return;
      const revision = (next as BubbleSnapshot & { revision?: number }).revision ?? revisionRef.current + 1;
      if (revision >= revisionRef.current) {
        revisionRef.current = revision;
        setState(next);
      }
      setNote(localizeMessage(localeRef.current, next.message));
      if (action === 'toggle' && next.actionMessage === 'bubble_other_apps') {
        const message = localizeMessage(localeRef.current, next.actionMessage);
        ToastAndroid.show(message, ToastAndroid.SHORT);
        AccessibilityInfo.announceForAccessibility(message);
      }
      if (action === 'refine' && !next.expanded) ToastAndroid.show(t('editInApp'), ToastAndroid.SHORT);
    } catch {
      if (mountedRef.current) setNote(t('readFailed'));
    } finally {
      if (!control) {
        actionRef.current = false;
        if (mountedRef.current) setAction(null);
      }
    }
  }

  async function hide() {
    await Erase.hideBubble();
  }

  async function collapse(message: string) {
    // Callers check the surface lifetime synchronously before entering this helper.
    // Collapse is idempotent; a delayed success can never reopen the panel.
    const next = await Erase.bubbleAction('collapse');
    if (!mountedRef.current) return;
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
      if (!mountedRef.current) return;
      if (status === 'copied') await collapse(t('copied'));
      else setNote(t('nothingToCopy'));
    } catch {
      if (mountedRef.current) setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      if (mountedRef.current) setAction(null);
    }
  }

  async function save() {
    if (!state.previewPath || savingRef.current || actionRef.current) return;
    const path = state.previewPath as string;
    savingRef.current = true;
    setAction('save');
    try {
      const status = await Erase.saveToGallery(path);
      if (!mountedRef.current) return;
      const message = t(savePhase(status) === 'saved' ? 'saved' : 'saveFailed');
      if (savePhase(status) === 'saved') await collapse(message);
      else setNote(message);
    } catch {
      if (mountedRef.current) setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      if (mountedRef.current) setAction(null);
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
          <Text accessibilityRole="progressbar" accessibilityLabel={t('processing')} accessibilityValue={{ min: 0, max: 100, now: displayedPercent }} className="mt-3 text-center text-lg font-semibold" style={{ color: colors.text }}>{displayedPercent}%</Text>
        </View>
      ) : null}
      {result ? (
        <View>
          {state.previewPath ? (
            <BubblePreview path={state.previewPath} background={colors.surfaceRaised} label={t('done')} />
          ) : null}
          <Notice message={notice} />
          <Action label={t('copy')} loading={action === 'copy'} disabled={action != null || !state.previewPath} onPress={() => { void copy(); }} colors={colors} />
          <Action label={t('save')} loading={action === 'save'} disabled={action != null || !state.previewPath} onPress={() => { void save(); }} colors={colors} />
          {state.editReady ? <Action label={t('editInApp')} loading={action === 'openEditor'} disabled={action != null} onPress={() => { void run('refine'); }} colors={colors} /> : null}
          <Action label={t('another')} disabled={action != null} onPress={() => { void run('again'); }} colors={colors} />
        </View>
      ) : null}
      {!busy && !result ? (
        <View>
          <Action label={t('fromClipboard')} loading={state.readingClipboard} disabled={state.readingClipboard} onPress={() => { void run('clipboard'); }} colors={colors} />
          <Action label={t('fromGallery')} disabled={state.readingClipboard} onPress={() => { void run('gallery'); }} colors={colors} />
          <Action label={t('file')} disabled={state.readingClipboard} onPress={() => { void run('file'); }} colors={colors} />
          <Notice message={notice} />
        </View>
      ) : null}
    </ScrollView>
  );

  return (
    <View testID="bubble-surface" style={{ width: geometry?.surfaceWidthDp ?? 640, height: geometry?.surfaceHeightDp ?? 400, direction: 'ltr' }}>
      <View testID="bubble-chip" style={{ direction: 'ltr', position: 'absolute', left: chipLeft, top: chipTop, width: chipSize, height: chipSize }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('cutBackground')}
          className="items-center justify-center"
          style={{ width: chipSize, height: chipSize }}
          onPress={() => { void run('toggle'); }}>
          <BubbleGlyph size={visualSize} opacity={appearance.opacity} compact={compact} dot={appearance.shrinkToDot} reducedMotion={reducedMotion} />
        </Pressable>

      </View>
      {expanded ? (
        <Animated.View testID="bubble-panel" style={[{ direction: 'ltr', position: 'absolute', left: anchorEnd ? chipLeft - 12 - panelWidth : chipLeft + chipSize + 12, top: 0, width: panelWidth, height: panelHeight }, panelStyle]}>
          {actions}
        </Animated.View>
      ) : null}
    </View>
  );
}

function Action({ label, onPress, disabled, loading = false, colors }: { label: string; onPress: () => void; disabled: boolean; loading?: boolean; colors: ReturnType<typeof useTheme>['colors'] }) {
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
