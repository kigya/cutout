import { memo, useEffect, useRef, useState } from 'react';
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
import type { PermissionKind } from '../logic/permissions';
import { Erase, type HomeSnapshot } from '../native/Erase';
import { useTheme } from '../theme/ThemeProvider';

type Source = 'gallery' | 'file' | 'clipboard' | 'fixture';

type Props = {
  home: HomeSnapshot;
  busy: boolean;
  notice: string;
  onRefresh: () => void;
  onSettings: () => void;
  onCut: (source: Source) => void;
};

export function HomeScreen({ home, busy, notice, onRefresh, onSettings, onCut }: Props) {
  const { t } = useLocale();
  const { colors } = useTheme();
  const insets = useSystemBars();
  const [sheet, setSheet] = useState<PermissionKind | null>(null);
  const [sources, setSources] = useState(false);
  const pendingSource = useRef<Source | null>(null);
  const [overlayInfo, setOverlayInfo] = useState(false);
  const [pendingEnable, setPendingEnable] = useState(false);
  const [showBubbleDemo, setShowBubbleDemo] = useState(true);
  const [bubbleNotice, setBubbleNotice] = useState('');
  const detail = sheet ? permissionCopy(sheet) : null;
  const detailBody = sheet === 'notifications' ? t('notificationWhy') : detail ? t(detail.body) : '';
  const statusLabel = {
    granted: t('statusOn'),
    denied: t('statusOff'),
    unknown: t('statusUnknown'),
  } as const;

  async function enableBubble() {
    setOverlayInfo(false);
    const overlay = home.rows.find(row => row.kind === 'overlay');
    try {
      if (overlay?.status === 'granted') {
        await Erase.setBubble(true);
        onRefresh();
        return;
      }
      setPendingEnable(true);
      await Erase.openPermission('overlay');
    } catch {
      setPendingEnable(false);
      setBubbleNotice(t('readFailed'));
      onRefresh();
    }
  }

  async function toggleBubble(next: boolean) {
    if (next && home.rows.find(row => row.kind === 'overlay')?.status !== 'granted') {
      setOverlayInfo(true);
      return;
    }
    await Erase.setBubble(bubbleEnabledAfter('set', next));
    onRefresh();
  }

  useEffect(() => {
    const subscription = AppState.addEventListener('change', async state => {
      if (state !== 'active') return;
      onRefresh();
      if (!pendingEnable) return;
      const snapshot = await Erase.getHome().catch(() => null);
      setPendingEnable(false);
      if (snapshot?.rows.find(row => row.kind === 'overlay')?.status === 'granted') {
        await Erase.setBubble(true).catch(() => setBubbleNotice(t('readFailed')));
      }
      onRefresh();
    });
    return () => subscription.remove();
  }, [onRefresh, pendingEnable]);

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
      {Platform.OS === 'android' && showBubbleDemo ? <BubbleDemo onDismiss={() => setShowBubbleDemo(false)} /> : null}

      {home.rows.length > 0 ? <Text className="mb-2 mt-8 text-sm font-medium" style={{ color: colors.textMuted }}>{t('permissions')}</Text> : null}
      {home.rows.filter(row => row.kind !== 'overlay' || Platform.OS === 'android').map(row => (
        <PermissionRow
          key={row.kind}
          kind={row.kind}
          title={t(permissionCopy(row.kind).title)}
          status={statusLabel[row.status]}
          statusTone={row.status === 'granted' ? 'good' : row.status === 'denied' ? 'warn' : 'neutral'}
          needLabel={row.need === 'requiredForBubble' ? t('required') : t('optional')}
          required={row.need === 'requiredForBubble'}
          whyLabel={t('why')}
          onOpen={() => { if (row.kind === 'notifications' && Platform.OS === 'android' && Platform.Version >= 33) { void PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS).then(onRefresh); } else { void Erase.openPermission(row.kind).then(onRefresh); } }}
          onInfo={() => setSheet(row.kind)}
        />
      ))}

      {__DEV__ ? (
        <Pressable accessibilityRole="button" className="mt-6 h-12 justify-center" onPress={() => onCut('fixture')}>
          <Text className="text-sm" style={{ color: colors.textMuted }}>{t('sampleFrame')}</Text>
        </Pressable>
      ) : null}

        <Sheet
        title={detail ? t(detail.title) : ''}
        body={detailBody}
        visible={detail != null}
        closeLabel={t('gotIt')}
        onClose={() => setSheet(null)}
      />
      <Sheet
        title={t('bubbleSetupTitle')}
        body={t('bubbleSetupBody')}
        visible={overlayInfo}
        closeLabel={t('gotIt')}
        actionLabel={t('continueToSettings')}
        onAction={() => { void enableBubble(); }}
        onClose={() => setOverlayInfo(false)}
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
  onOpen: () => void;
  onInfo: () => void;
}) {
  const { colors } = useTheme();
  return (
    <View className="mb-2 min-h-[72px] flex-row items-center rounded-3xl border px-3 py-2" style={{ backgroundColor: colors.surface, borderColor: colors.border }}>
      <Pressable accessibilityRole="button" accessibilityLabel={`${title}, ${status}, ${needLabel}`} className="min-h-12 flex-1 flex-row items-center" onPress={onOpen}>
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
      <Pressable accessibilityRole="button" accessibilityLabel={`${whyLabel}: ${title}`} className="h-12 w-12 items-center justify-center rounded-full" style={{ backgroundColor: colors.surfaceRaised }} onPress={onInfo}>
        <InfoIcon />
      </Pressable>
    </View>
  );
});
