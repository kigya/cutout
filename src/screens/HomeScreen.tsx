import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Modal, PermissionsAndroid, Platform, Pressable, Switch, Text, View } from 'react-native';
import { Badge } from '../components/ui/badge';
import { BubbleDemo } from '../components/BubbleDemo';
import { Button } from '../components/ui/button';
import { GearIcon, InfoIcon, OptionalMark, PermissionIcon, RequiredMark } from '../components/ui/icons';
import { Screen } from '../components/ui/screen';
import { Sheet } from '../components/ui/sheet';
import { useLocale } from '../i18n/LocaleGate';
import { bubbleEnabledAfter } from '../logic/bubble';
import { canUseClipboard } from '../logic/clipboard';
import { SYSTEM_BAR_GAP, useSystemBars } from '../logic/systemBars';
import { permissionCopy } from '../logic/permissionCopy';
import { permissionNeed, permissionNeedsHelp, type PermissionKind } from '../logic/permissions';
import { Erase, type HomeSnapshot } from '../native/Erase';
import { useTheme } from '../theme/ThemeProvider';

type Source = 'gallery' | 'file' | 'clipboard' | 'fixture';

type Props = {
  home: HomeSnapshot;
  busy: boolean;
  notice: string;
  diagnosticsNoticePending?: boolean;
  onDismissDiagnosticsNotice?: () => void;
  onPrivacy?: () => void;
  onRefresh: () => void;
  onSettings: () => void;
  onCut: (source: Source) => void;
};

type PermissionSheetState = { kind: PermissionKind; blocked: boolean; enableBubble: boolean; setup?: boolean };

type SettingsAttempt = {
  kind: PermissionKind;
  enableBubble: boolean;
  launched: boolean;
  sawBackground: boolean;
  historyReady: boolean;
  attemptId?: string;
};

export function HomeScreen({ home, busy, notice, diagnosticsNoticePending = false, onDismissDiagnosticsNotice, onPrivacy, onRefresh, onSettings, onCut }: Props) {
  const { t } = useLocale();
  const { colors } = useTheme();
  const insets = useSystemBars();
  const [sheet, setSheet] = useState<PermissionSheetState | null>(null);
  const [sources, setSources] = useState(false);
  const pendingSource = useRef<Source | null>(null);
  const [pendingEnable, setPendingEnable] = useState(false);
  const pendingEnableRef = useRef(false);
  const settingsAttemptRef = useRef<SettingsAttempt | null>(null);
  const runtimeRequestRef = useRef(false);
  const [showBubbleDemo, setShowBubbleDemo] = useState(true);
  const [bubbleNotice, setBubbleNotice] = useState('');
  const latestHomeRef = useRef(home);
  latestHomeRef.current = home;
  const detail = sheet ? permissionCopy(sheet.kind) : null;
  const detailBody = sheet?.blocked
    ? t('permissionBlockedBody')
    : sheet?.kind === 'notifications'
      ? t('notificationWhy')
      : detail ? t(detail.body) : '';
  const detailTitle = sheet?.blocked ? t('permissionBlockedTitle') : detail ? t(detail.title) : '';
  const statusLabel = {
    granted: t('statusOn'),
    denied: t('statusOff'),
    unknown: t('statusUnknown'),
  } as const;

  function updatePendingEnable(value: boolean) {
    pendingEnableRef.current = value;
    setPendingEnable(value);
  }

  const finishSettingsAttempt = useCallback(async (attempt: SettingsAttempt) => {
    if (settingsAttemptRef.current !== attempt) return;
    settingsAttemptRef.current = null;

    if (attempt.attemptId) {
      await Erase.completePermissionRequest?.(attempt.kind, attempt.attemptId, false).catch(() => undefined);
    }
    const snapshot = await Erase.getHome().catch(() => null);
    const row = snapshot?.rows.find(candidate => candidate.kind === attempt.kind);
    if (attempt.enableBubble) {
      updatePendingEnable(false);
      if (row?.status === 'granted') {
        await Erase.setBubble(true).catch(() => setBubbleNotice(t('readFailed')));
      } else if (row && permissionNeedsHelp(row)) {
        setSheet({ kind: attempt.kind, blocked: true, enableBubble: true });
      }
    } else if (row && permissionNeedsHelp(row)) {
      setSheet({ kind: attempt.kind, blocked: true, enableBubble: false });
    }
    onRefresh();
  }, [onRefresh, t]);

  const openPermissionSettings = useCallback(async (kind: PermissionKind, enableAfterGrant: boolean) => {
    if (settingsAttemptRef.current) return;
    const attempt: SettingsAttempt = {
      kind,
      enableBubble: enableAfterGrant,
      launched: false,
      sawBackground: false,
      historyReady: false,
    };
    settingsAttemptRef.current = attempt;
    if (enableAfterGrant) updatePendingEnable(true);

    try {
      await Erase.openPermission(kind);
      attempt.launched = true;
      attempt.attemptId = await Erase.beginPermissionRequest?.(kind).catch(() => undefined);
      attempt.historyReady = true;
      if (attempt.sawBackground && AppState.currentState === 'active') {
        await finishSettingsAttempt(attempt);
      }
    } catch {
      if (settingsAttemptRef.current === attempt) settingsAttemptRef.current = null;
      if (enableAfterGrant) updatePendingEnable(false);
      setBubbleNotice(t('readFailed'));
      onRefresh();
    }
  }, [finishSettingsAttempt, onRefresh, t]);

  const enableBubble = useCallback(async () => {
    const latestHome = latestHomeRef.current;
    const overlay = latestHome.rows.find(row => row.kind === 'overlay');
    if (overlay?.status === 'granted') {
      await Erase.setBubble(true);
      onRefresh();
      return;
    }
    updatePendingEnable(true);
    if (overlay && permissionNeedsHelp(overlay)) {
      setSheet({ kind: 'overlay', blocked: true, enableBubble: true });
    } else {
      setSheet({ kind: 'overlay', blocked: false, enableBubble: true, setup: true });
    }
  }, [onRefresh]);

  const toggleBubble = useCallback(async (next: boolean) => {
    if (next) {
      await enableBubble();
      return;
    }
    await Erase.setBubble(bubbleEnabledAfter('set', next));
    updatePendingEnable(false);
    onRefresh();
  }, [enableBubble, onRefresh]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', async state => {
      const attempt = settingsAttemptRef.current;
      if (state !== 'active') {
        if (attempt) attempt.sawBackground = true;
        return;
      }
      onRefresh();
      if (attempt?.launched && attempt.historyReady && attempt.sawBackground) {
        await finishSettingsAttempt(attempt);
      }
    });
    return () => subscription.remove();
  }, [finishSettingsAttempt, onRefresh]);

  const requestNotifications = useCallback(async () => {
    if (runtimeRequestRef.current) return;
    runtimeRequestRef.current = true;
    let attemptId: string | undefined;
    try {
      attemptId = await Erase.beginPermissionRequest?.('notifications').catch(() => undefined);
      const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
      if (attemptId) {
        await Erase.completePermissionRequest?.(
          'notifications',
          attemptId,
          result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN,
        ).catch(() => undefined);
      }
      const snapshot = await Erase.getHome().catch(() => null);
      const row = snapshot?.rows.find(candidate => candidate.kind === 'notifications');
      if (row && permissionNeedsHelp(row)) {
        setSheet({ kind: 'notifications', blocked: true, enableBubble: false });
      }
      onRefresh();
    } catch {
      setBubbleNotice(t('readFailed'));
      onRefresh();
    } finally {
      runtimeRequestRef.current = false;
    }
  }, [onRefresh, t]);

  const openRow = useCallback((kind: PermissionKind) => {
    const latestHome = latestHomeRef.current;
    const row = latestHome.rows.find(candidate => candidate.kind === kind);
    if (!row) return;
    if (permissionNeedsHelp(row)) {
      setSheet({ kind: row.kind, blocked: true, enableBubble: row.kind === 'overlay' && latestHome.bubbleEnabled });
      return;
    }
    if (row.kind === 'notifications' && Platform.OS === 'android' && Platform.Version >= 33 && row.status !== 'granted') {
      void requestNotifications();
      return;
    }
    void openPermissionSettings(row.kind, row.kind === 'overlay' && latestHome.bubbleEnabled);
  }, [openPermissionSettings, requestNotifications]);

  const openRowInfo = useCallback((kind: PermissionKind) => {
    const latestHome = latestHomeRef.current;
    const row = latestHome.rows.find(candidate => candidate.kind === kind);
    if (!row) return;
    setSheet({ kind: row.kind, blocked: permissionNeedsHelp(row), enableBubble: row.kind === 'overlay' && (latestHome.bubbleEnabled || pendingEnableRef.current) });
  }, []);

  function closeSources() {
    pendingSource.current = null;
    setSources(false);
  }

  function chooseSource(source: Source) {
    if (Platform.OS === 'ios') pendingSource.current = source;
    setSources(false);
    if (Platform.OS !== 'ios') onCut(source);
  }

  function sourceDismissed() {
    const next = pendingSource.current;
    pendingSource.current = null;
    if (next) onCut(next);
  }

  return (
    <Screen scroll>
      <View className="flex-row items-center justify-between">
        <Text className="text-3xl font-semibold" style={{ color: colors.text }}>Cutout</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('settings')}
          className="h-12 w-12 items-center justify-center rounded-full"
          style={{ backgroundColor: colors.surface }}
          onPress={onSettings}>
          <GearIcon />
        </Pressable>
      </View>
      <Text className="mt-3 text-base leading-6" style={{ color: colors.textMuted }}>{t('localProcessing')}</Text>
      {diagnosticsNoticePending ? (
        <View className="mt-4 rounded-3xl border p-4" style={{ backgroundColor: colors.surface, borderColor: colors.border }}>
          <Text accessibilityRole="header" className="text-base font-semibold" style={{ color: colors.text }}>{t('diagnosticsNoticeTitle')}</Text>
          <Text className="mt-2 text-sm leading-5" style={{ color: colors.textMuted }}>{t('diagnosticsNoticeBody')}</Text>
          <View className="mt-3 gap-2">
            <Button label={t('diagnosticsNoticeSettings')} tone="ghost" onPress={onSettings} />
            {onPrivacy ? <Button label={t('privacyPolicy')} tone="ghost" onPress={onPrivacy} /> : null}
            <Button label={t('diagnosticsNoticeDismiss')} tone="ghost" onPress={() => onDismissDiagnosticsNotice?.()} />
          </View>
        </View>
      ) : null}
      {notice ? <Text className="mt-3 text-sm" style={{ color: colors.text }}>{notice}</Text> : null}
      {bubbleNotice ? <Text accessibilityRole="alert" className="mt-2 text-sm" style={{ color: colors.warning }}>{bubbleNotice}</Text> : null}

      <View className="mt-6">
        <Button label={busy ? t('cutting') : t('cutHere')} disabled={busy} onPress={() => setSources(true)} />
      </View>

      {Platform.OS === 'android' && home.bubbleSupported !== false ? <View className="mt-4 flex-row items-center justify-between rounded-3xl border px-4 py-4" style={{ backgroundColor: colors.surface, borderColor: colors.border }}>
        <View className="flex-1 pr-3">
          <Text className="text-base font-semibold" style={{ color: colors.text }}>{t('bubble')} · {t('optional')}</Text>
          <Text className="mt-1 text-sm" style={{ color: colors.textMuted }}>{t('bubbleOver')}</Text>
        </View>
        <Switch accessibilityLabel={t('showBubble')} value={home.bubbleEnabled} onValueChange={value => { setBubbleNotice(''); void toggleBubble(value).catch(() => { setBubbleNotice(t('readFailed')); onRefresh(); }); }} />
      </View> : null}
      {Platform.OS === 'android' && !home.bubbleEnabled && !pendingEnable && showBubbleDemo && home.bubbleSupported !== false ? (
        <View className="mt-3">
          <BubbleDemo onDismiss={() => setShowBubbleDemo(false)} />
          <Text className="mt-4 text-base font-semibold" style={{ color: colors.text }}>{t('bubblePromoTitle')}</Text>
          <Text className="mt-1 text-sm leading-5" style={{ color: colors.textMuted }}>{t('bubblePromoBody')}</Text>
          <View className="mt-3"><Button label={t('bubblePromoEnable')} onPress={() => { void enableBubble().catch(() => setBubbleNotice(t('readFailed'))); }} /></View>
        </View>
      ) : null}

      {home.rows.some(row => row.kind !== 'overlay' || Platform.OS === 'android') ? <Text className="mb-2 mt-8 text-sm font-medium" style={{ color: colors.textMuted }}>{t('permissions')}</Text> : null}
      {home.rows.filter(row => row.kind !== 'overlay' || Platform.OS === 'android').map(row => (
        <PermissionRow
          key={row.kind}
          kind={row.kind}
          title={t(permissionCopy(row.kind).title)}
          status={statusLabel[row.status]}
          statusTone={row.status === 'granted' ? 'good' : row.status === 'denied' ? 'warn' : 'neutral'}
          needLabel={permissionNeed(row.kind, home.bubbleEnabled || pendingEnable) === 'requiredForBubble' ? t('required') : t('optional')}
          required={permissionNeed(row.kind, home.bubbleEnabled || pendingEnable) === 'requiredForBubble'}
          whyLabel={t('why')}
          onOpen={openRow}
          onInfo={openRowInfo}
        />
      ))}

      {__DEV__ ? (
        <Pressable accessibilityRole="button" className="mt-6 h-12 justify-center" onPress={() => onCut('fixture')}>
          <Text className="text-sm" style={{ color: colors.textMuted }}>{t('sampleFrame')}</Text>
        </Pressable>
      ) : null}

        <Sheet
        title={detailTitle}
        body={detailBody}
        visible={detail != null && (sheet?.blocked === true || sheet?.kind !== 'overlay' || sheet?.setup !== true)}
        closeLabel={t(sheet?.blocked ? 'notNow' : 'gotIt')}
        icon={sheet ? <PermissionIcon kind={sheet.kind} /> : undefined}
        actionLabel={sheet?.blocked ? t('openSettings') : undefined}
        onAction={sheet?.blocked ? () => {
          const current = sheet;
          setSheet(null);
          void openPermissionSettings(current.kind, current.enableBubble);
        } : undefined}
        onClose={() => {
          if (sheet?.enableBubble) updatePendingEnable(false);
          setSheet(null);
        }}
      />
      <Sheet
        title={t('bubbleSetupTitle')}
        body={t('bubbleSetupBody')}
        visible={sheet?.kind === 'overlay' && sheet.setup === true && !sheet.blocked}
        closeLabel={t('notNow')}
        icon={<PermissionIcon kind="overlay" />}
        actionLabel={t('continueToSettings')}
        onAction={() => { setSheet(null); void openPermissionSettings('overlay', true); }}
        onClose={() => { updatePendingEnable(false); setSheet(null); }}
      />
      <Modal transparent animationType="none" visible={sources} onDismiss={sourceDismissed} onRequestClose={closeSources}>
        <Pressable
          accessible={false}
          className="flex-1 justify-end"
          style={{
            backgroundColor: colors.scrim,
            paddingTop: insets.top + SYSTEM_BAR_GAP,
            paddingBottom: insets.bottom + SYSTEM_BAR_GAP,
            paddingLeft: insets.left + 20,
            paddingRight: insets.right + 20,
          }}
          onPress={closeSources}>
          <Pressable accessible={false} className="rounded-3xl border p-4" style={{ backgroundColor: colors.surface, borderColor: colors.border }} onPress={() => undefined}>
            <Text className="mb-3 text-lg font-semibold" style={{ color: colors.text }}>{t('choosePicture')}</Text>
            <View className="gap-2">
              <Button label={t('gallery')} onPress={() => chooseSource('gallery')} />
              <Button label={t('file')} tone="ghost" onPress={() => chooseSource('file')} />
              <Button
                label={t('clipboard')}
                tone="ghost"
                disabled={!canUseClipboard(home.clipboardReady)}
                onPress={() => chooseSource('clipboard')}
              />
              <Button label={t('back')} tone="ghost" onPress={closeSources} />
            </View>
          </Pressable>
        </Pressable>
      </Modal>
    </Screen>
  );
}

const PermissionRow = memo(function PermissionRow({
  kind,
  title,
  status,
  statusTone,
  needLabel,
  required,
  whyLabel,
  onOpen,
  onInfo,
}: {
  kind: PermissionKind;
  title: string;
  status: string;
  statusTone: 'good' | 'warn' | 'neutral';
  needLabel: string;
  required: boolean;
  whyLabel: string;
  onOpen: (kind: PermissionKind) => void;
  onInfo: (kind: PermissionKind) => void;
}) {
  const { colors } = useTheme();
  return (
    <View className="mb-2 min-h-[72px] flex-row items-center rounded-3xl border px-3 py-2" style={{ backgroundColor: colors.surface, borderColor: colors.border }}>
      <Pressable accessibilityRole="button" accessibilityLabel={`${title}, ${status}, ${needLabel}`} className="min-h-12 flex-1 flex-row items-center" onPress={() => onOpen(kind)}>
        <View className="me-3 h-10 w-10 items-center justify-center rounded-2xl" style={{ backgroundColor: colors.surfaceRaised }}>
          <PermissionIcon kind={kind} />
        </View>
        <View className="flex-1">
        <Text className="text-base font-semibold" style={{ color: colors.text }}>{title}</Text>
        <View className="mt-2 flex-row flex-wrap gap-2">
          <Badge label={status} tone={statusTone} />
          <View className="flex-row items-center gap-1 rounded-full px-2 py-1" style={{ backgroundColor: required ? (colors.warning === '#FFD08A' ? '#472E0C' : '#FFF0D6') : colors.surfaceRaised }}>
            {required ? <RequiredMark /> : <OptionalMark />}
            <Text className="text-xs" style={{ color: required ? colors.warning : colors.textMuted }}>{needLabel}</Text>
          </View>
        </View>
        </View>
      </Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel={`${whyLabel}: ${title}`} className="h-12 w-12 items-center justify-center rounded-full" style={{ backgroundColor: colors.surfaceRaised }} onPress={() => onInfo(kind)}>
        <InfoIcon />
      </Pressable>
    </View>
  );
});
