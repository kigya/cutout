import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, BackHandler, DeviceEventEmitter, Linking, View } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { ThemeProvider, useTheme } from './theme/ThemeProvider';
import { Sheet } from './components/ui/sheet';
import { DEFAULT_PREFERENCES, readPreferences, track, writePreferences } from './logic/telemetry';
import type { AppPreferences } from './native/Erase';
import { LocaleGate, useLocale } from './i18n/LocaleGate';
import { localizeMessage } from './i18n/catalogs';
import { clampPercent } from './logic/progress';
import { backTarget } from './logic/navigation';
import { canEdit, Erase, type EditSession, type HomeSnapshot } from './native/Erase';
import { SplashScreen } from './components/SplashScreen';
import { EditorScreen } from './screens/EditorScreen';
import { HomeScreen } from './screens/HomeScreen';
import { LanguageScreen } from './screens/LanguageScreen';
import { ProcessingScreen } from './screens/ProcessingScreen';
import { ResultScreen } from './screens/ResultScreen';
import { SettingsScreen } from './screens/SettingsScreen';

type Source = 'gallery' | 'file' | 'clipboard' | 'fixture';

type Route =
  | { name: 'home' }
  | { name: 'settings' }
  | { name: 'language' }
  | { name: 'processing'; preview: string | null; percent: number; stage?: 'preparing'|'inference'|'exporting'|'ready'|'failed'|'idle' }
  | { name: 'result'; session: EditSession }
  | { name: 'editor'; session: EditSession };

const EMPTY: HomeSnapshot = { rows: [], bubbleEnabled: false, showXiaomiTip: false, clipboardReady: false };

export default function App() {
  const [preferences, setPreferences] = useState<AppPreferences>(DEFAULT_PREFERENCES);
  useEffect(() => { void readPreferences().then(setPreferences); }, []);
  const updatePreferences = async (next: AppPreferences) => {
    await writePreferences(next);
    setPreferences(next);
  };
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <ThemeProvider mode={preferences.theme}>
          <LocaleGate>
            <Root preferences={preferences} onPreferences={updatePreferences} />
          </LocaleGate>
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

function Root({ preferences, onPreferences }: { preferences: AppPreferences; onPreferences: (value: AppPreferences) => Promise<void> }) {
  const { locale, t } = useLocale();
  const { colors } = useTheme();
  const [privacyOpen, setPrivacyOpen] = useState(false);
  const [booting, setBooting] = useState(true);
  const finishBoot = useCallback(() => setBooting(false), []);
  const [route, setRoute] = useState<Route>({ name: 'home' });
  const [home, setHome] = useState<HomeSnapshot>(EMPTY);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const cutToken = useRef(0);
  const routeRef = useRef(route);
  routeRef.current = route;
  const cancelProcessing = useCallback(() => {
    cutToken.current += 1;
    track('cutout_cancelled', { surface: 'app', stage: 'processing' });
    void Erase.cancelCut();
    setBusy(false);
    setRoute({ name: 'home' });
  }, []);

  const refresh = useCallback(async () => {
    setHome(await Erase.getHome());
  }, []);

  const openEdit = useCallback(async () => {
    const pending = await Erase.consumePendingEdit();
    if (canEdit(pending)) setRoute({ name: 'editor', session: pending });
  }, []);

  useEffect(() => {
    void refresh();
    void Erase.prepareModel().catch(() => undefined);
    void openEdit();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') void refresh();
    });
    const edits = DeviceEventEmitter.addListener('eraseOpenEdit', () => {
      void openEdit();
    });
    const bubbleFlag = DeviceEventEmitter.addListener('bubbleEnabled', event => {
      const enabled = (event as { enabled?: boolean }).enabled === true;
      setHome(current => ({ ...current, bubbleEnabled: enabled }));
    });
    const progress = DeviceEventEmitter.addListener('eraseProgress', event => {
      const percent = clampPercent((event as { percent?: number }).percent ?? 0);
      setRoute(current => (current.name === 'processing' ? { ...current, percent, stage: percent < 28 ? 'preparing' : percent < 92 ? 'inference' : 'exporting' } : current));
    });
    const preview = DeviceEventEmitter.addListener('erasePreview', event => {
      const path = (event as { path?: string }).path ?? null;
      setRoute(current => (current.name === 'processing' && path ? { ...current, preview: path } : current));
    });
    const back = BackHandler.addEventListener('hardwareBackPress', () => {
      if (routeRef.current.name === 'processing') {
        cancelProcessing();
        return true;
      }
      const next = backTarget(routeRef.current.name);
      if (!next) return false;
      setRoute(next === 'settings' ? { name: 'settings' } : { name: 'home' });
      return true;
    });
    return () => {
      subscription.remove();
      edits.remove();
      bubbleFlag.remove();
      progress.remove();
      preview.remove();
      back.remove();
    };
  }, [cancelProcessing, openEdit, refresh]);

  useEffect(() => {
    let focusedAt = AppState.currentState === 'active' ? Date.now() : null;
    track('screen_view', { screen_name: route.name });
    const finish = () => {
      if (focusedAt != null) track('screen_duration', { screen_name: route.name, duration_ms: Date.now() - focusedAt });
      focusedAt = null;
    };
    const listener = AppState.addEventListener('change', state => {
      if (state === 'active' && focusedAt == null) focusedAt = Date.now();
      else if (state !== 'active') finish();
    });
    return () => { finish(); listener.remove(); };
  }, [route.name]);

  async function beginCut(source: Source) {
    const started = Date.now();
    track('cutout_started', { source, surface: 'app' });
    const token = ++cutToken.current;
    setBusy(true);
    setNotice('');
    setRoute({ name: 'processing', preview: null, percent: 8 });
    try {
      const session = source === 'fixture'
        ? await Erase.cutFixture()
        : source === 'clipboard'
          ? await Erase.cutClipboard()
          : await Erase.pickAndCut(source);
      if (token !== cutToken.current) return;
      if (session.message === 'cancelled') {
        track('cutout_cancelled', { source, surface: 'app' });
        setRoute({ name: 'home' });
        return;
      }
      track(session.status === 'failed' ? 'cutout_failed' : 'cutout_completed', { source, surface: 'app', duration_ms: Date.now() - started, result: session.status ?? 'ready' });
      if (session.status === 'manual' && canEdit(session)) {
        setRoute({ name: 'editor', session });
        return;
      }
      if (session.status === 'failed') {
        setNotice(localizeMessage(locale, session.message));
        setRoute({ name: 'home' });
        return;
      }
      setRoute({ name: 'result', session });
    } catch {
      if (token === cutToken.current) {
        setNotice(t('readFailed'));
        setRoute({ name: 'home' });
        track('cutout_failed', { source, surface: 'app' });
      }
    } finally {
      if (token === cutToken.current) setBusy(false);
    }
  }

  if (booting) return <SplashScreen onDone={finishBoot} />;

  return (
    <View className="flex-1" style={{ backgroundColor: colors.background }}>
      <Animated.View key={route.name} entering={FadeIn.duration(180)} className="flex-1">
      {route.name === 'home' ? (
        <HomeScreen
          home={home}
          busy={busy}
          notice={notice}
          onRefresh={() => { void refresh(); }}
          onSettings={() => setRoute({ name: 'settings' })}
          onCut={source => { void beginCut(source); }}
        />
      ) : null}
      {route.name === 'settings' ? (
        <SettingsScreen
          showXiaomiTip={home.showXiaomiTip}
          notice={notice}
          themeMode={preferences.theme}
          onThemeModeChange={theme => { void onPreferences({ ...preferences, theme }).catch(() => setNotice(t('saveFailed'))); }}
          analyticsEnabled={preferences.analytics}
          crashEnabled={preferences.crashes}
          onAnalyticsChange={analytics => { void onPreferences({ ...preferences, analytics }).catch(() => setNotice(t('saveFailed'))); }}
          onCrashChange={crashes => { void onPreferences({ ...preferences, crashes }).catch(() => setNotice(t('saveFailed'))); }}
          onOpenPrivacy={() => setPrivacyOpen(true)}
          onFeedback={() => { void Linking.openURL('mailto:kirillborichevskiy@gmail.com?subject=Cutout%20feedback').catch(() => setNotice(t('feedbackFailed'))); }}
          onBack={() => setRoute({ name: 'home' })}
          onLanguage={() => setRoute({ name: 'language' })}
        />
      ) : null}
      {route.name === 'language' ? <LanguageScreen onBack={() => setRoute({ name: 'settings' })} /> : null}
      {route.name === 'processing' ? (
        <ProcessingScreen stage={route.stage} previewPath={route.preview} percent={route.percent} onCancel={cancelProcessing} />
      ) : null}
      {route.name === 'result' ? (
        <ResultScreen
          session={route.session}
          onBack={() => setRoute({ name: 'home' })}
          onEdit={() => setRoute({ name: 'editor', session: route.session })}
        />
      ) : null}
      {route.name === 'editor' && canEdit(route.session) ? (
        <EditorScreen
          originalPath={route.session.originalPath}
          cutoutPath={route.session.cutoutPath}
          width={route.session.width}
          height={route.session.height}
          note={localizeMessage(locale, route.session.message)}
          onBack={() => setRoute({ name: 'home' })}
        />
      ) : null}
      </Animated.View>
      <Sheet title={t('privacyPolicy')} body={t('privacyBody')} visible={privacyOpen} closeLabel={t('gotIt')} onClose={() => setPrivacyOpen(false)} />
    </View>
  );
}
