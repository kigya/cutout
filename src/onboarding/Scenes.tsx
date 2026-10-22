import { useEffect, useRef, useState } from 'react';
import { Image, Platform, Pressable, Text, View } from 'react-native';
import Animated, { interpolate, useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { BubbleGlyph } from '../components/BubbleGlyph';
import { useTheme } from '../theme/ThemeProvider';
import { useLocale } from '../i18n/LocaleGate';
import { useSceneMotion } from './useSceneMotion';

const SHOE = require('../assets/onboarding-sneaker.png');

function Shoe({ width, height }: { width: number; height: number }) {
  return <Image accessible={false} source={SHOE} resizeMode="contain" style={{ width, height }} />;
}

function Checker() {
  const size = 320;
  const { isDark } = useTheme();
  return <View pointerEvents="none" style={{ position: 'absolute', width: size, height: size, flexDirection: 'row', flexWrap: 'wrap' }}>
    {Array.from({ length: 100 }, (_, i) => <View key={i} style={{ width: size / 10, height: size / 10, backgroundColor: (i + Math.floor(i / 10)) % 2 ? (isDark ? '#262F42' : '#E3EAF5') : (isDark ? '#1B2333' : '#F4F7FC') }} />)}
  </View>;
}

function Label({ children, blue = false }: { children: string; blue?: boolean }) {
  const { colors } = useTheme();
  return <View style={{ borderRadius: 16, paddingHorizontal: 13, paddingVertical: 8, backgroundColor: blue ? colors.primary : colors.surface, borderWidth: 1, borderColor: blue ? colors.primary : colors.border }}>
    <Text style={{ color: blue ? colors.onPrimary : colors.text, fontSize: 12, fontWeight: '600' }}>{children}</Text>
  </View>;
}

export function PhotoScene() {
  const { colors, isDark } = useTheme();
  const { t } = useLocale();
  const { progress } = useSceneMotion(5200);
  const wipe = useAnimatedStyle(() => {
    const p = interpolate(progress.value, [0, 0.12, 0.52, 0.85, 1], [0, 0, 1, 1, 0], 'clamp');
    return { transform: [{ translateX: p * 320 }] };
  });
  const beam = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.1, 0.16, 0.5, 0.57, 0.9, 1], [0, 0, 1, 1, 0, 0, 0]),
    transform: [{ translateX: interpolate(progress.value, [0.12, 0.52], [0, 320], 'clamp') }],
  }));
  const lift = useAnimatedStyle(() => ({ transform: [
    { translateY: interpolate(progress.value, [0, 0.48, 0.65, 0.88, 1], [0, 0, -12, -12, 0]) },
    { rotate: `${interpolate(progress.value, [0.48, 0.65, 0.88, 1], [0, -3, -3, 0], 'clamp')}deg` },
  ] }));
  const result = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0.48, 0.6, 0.86, 0.98], [0, 1, 1, 0], 'clamp'), transform: [{ translateY: interpolate(progress.value, [0.48, 0.6], [10, 0], 'clamp') }] }));
  return <View style={{ width: 340, height: 396, alignItems: 'center', justifyContent: 'center' }}>
    <View style={{ position: 'absolute', top: 20, left: 20, width: 300, height: 300, borderRadius: 150, backgroundColor: isDark ? '#12244B' : '#E3EDFF' }} />
    <View style={{ width: 320, height: 312, borderRadius: 34, overflow: 'hidden', borderWidth: 1, borderColor: colors.border, transform: [{ rotate: '-5deg' }], boxShadow: '0 18px 35px rgba(18,46,107,0.16)' }}>
      <Checker />
      <Animated.View style={[{ position: 'absolute', top: 0, left: 0, width: 320, height: 320, backgroundColor: isDark ? '#234171' : '#91BAEE', overflow: 'hidden' }, wipe]}>
        <View style={{ position: 'absolute', left: 120, top: -80, width: 280, height: 280, borderRadius: 140, backgroundColor: '#B3D1F5', opacity: 0.4 }} />
        <View style={{ position: 'absolute', left: -90, bottom: -100, width: 300, height: 240, borderRadius: 110, backgroundColor: '#2458B8', opacity: 0.25 }} />
      </Animated.View>
      <Animated.View style={[{ position: 'absolute', top: 26, left: 5 }, lift]}><Shoe width={306} height={252} /></Animated.View>
      <Animated.View style={[{ position: 'absolute', top: 0, left: 0, width: 3, height: 320, backgroundColor: '#FFFFFF', boxShadow: '0 0 18px #FFFFFF' }, beam]} />
    </View>
    {[0, 1, 2].map(i => <Fragment key={i} index={i} progress={progress} />)}
    <View style={{ position: 'absolute', left: 0, top: 46 }}><Label>{t('onboardingPhoto')}</Label></View>
    <Animated.View style={[{ position: 'absolute', right: 0, bottom: 20 }, result]}><Label blue>{t('onboardingPng')}</Label></Animated.View>
  </View>;
}

function Fragment({ index, progress }: { index: number; progress: ReturnType<typeof useSceneMotion>['progress'] }) {
  const motion = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.28, 0.4, 0.7, 0.85], [0, 0.75, 0, 0], 'clamp'),
    transform: [{ translateY: interpolate(progress.value, [0.28, 0.7], [0, -38 - index * 14], 'clamp') }, { translateX: interpolate(progress.value, [0.28, 0.7], [0, 20 + index * 12], 'clamp') }, { rotate: `${progress.value * (index + 1) * 50}deg` }],
  }));
  return <Animated.View style={[{ position: 'absolute', right: index * 25 + 10, top: 62 + index * 37, width: 16 + index * 3, height: 18, borderRadius: 5, backgroundColor: '#91BAEE' }, motion]} />;
}

export function ClipboardScene() {
  const { colors, isDark } = useTheme();
  const { t } = useLocale();
  const { progress } = useSceneMotion(6200);
  const sourceStyle = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0, 0.1, 0.7, 1], [0.4, 1, 1, 0.4]), transform: [{ translateY: interpolate(progress.value, [0, 0.2, 1], [8, 0, 8]) }, { rotate: '-7deg' }] }));
  const transferStyle = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0.15, 0.28, 0.58, 0.7], [0, 1, 1, 0], 'clamp'), transform: [{ translateX: interpolate(progress.value, [0.15, 0.58], [-72, 62], 'clamp') }, { translateY: interpolate(progress.value, [0.15, 0.58], [-56, 80], 'clamp') }, { scale: 0.55 }] }));
  const resultStyle = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0.48, 0.62, 0.9, 1], [0.2, 1, 1, 0.2], 'clamp'), transform: [{ scale: interpolate(progress.value, [0.48, 0.62, 0.68], [0.88, 1.04, 1], 'clamp') }, { rotate: '5deg' }] }));
  const lineStyle = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0.15, 0.35, 0.7, 1], [0.15, 1, 1, 0.15]) }));
  return <View style={{ width: 340, height: 396 }}>
    <View style={{ position: 'absolute', left: 20, top: 35, width: 290, height: 290, backgroundColor: isDark ? '#12244B' : '#E3EDFF', borderRadius: 145 }} />
    <Animated.View style={[{ position: 'absolute', top: 32, left: 10, width: 202, height: 156, borderRadius: 24, backgroundColor: '#91BAEE', borderWidth: 5, borderColor: colors.surface, boxShadow: '0 12px 25px rgba(18,46,107,0.12)' }, sourceStyle]}>
      <Shoe width={192} height={146} />
    </Animated.View>
    <Animated.View style={[{ position: 'absolute', left: 138, top: 152, height: 110, width: 60, borderRightWidth: 2, borderBottomWidth: 2, borderColor: colors.accent, borderBottomRightRadius: 28, borderStyle: 'dashed' }, lineStyle]} />
    <View style={{ position: 'absolute', right: 15, top: 125, padding: 10, borderRadius: 20, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border }}><Label blue>{t('clipboard')}</Label></View>
    <Animated.View style={[{ position: 'absolute', left: 55, top: 100 }, transferStyle]}><Shoe width={200} height={160} /></Animated.View>
    <Animated.View style={[{ position: 'absolute', right: 8, bottom: 26, width: 224, height: 150, borderRadius: 26, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, overflow: 'hidden', boxShadow: '0 16px 30px rgba(18,46,107,0.12)' }, resultStyle]}>
      <View style={{ flexDirection: 'row', gap: 6, padding: 12, alignItems: 'center' }}><View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: colors.primary }} /><Text style={{ color: colors.textMuted, fontSize: 11 }}>{t('onboardingMessage')}</Text></View>
      <View style={{ alignItems: 'flex-end', paddingEnd: 10 }}><Shoe width={180} height={100} /></View>
    </Animated.View>
    <View style={{ position: 'absolute', left: 4, top: 200 }}><Label>{t('copy')}</Label></View>
  </View>;
}

export function BubbleScene() {
  const { colors, isDark } = useTheme();
  const { t } = useLocale();
  const { progress, reduced, active } = useSceneMotion(6600);
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<'idle' | 'working' | 'ready'>('idle');
  const [manual, setManual] = useState(false);
  const finish = useRef<ReturnType<typeof setTimeout> | null>(null);
  const panel = useSharedValue(0);
  const isIos = Platform.OS === 'ios';
  useEffect(() => {
    if (!active || reduced || manual || isIos) return;
    const show = setTimeout(() => setOpen(true), 1500);
    const hide = setTimeout(() => setOpen(false), 4700);
    return () => { clearTimeout(show); clearTimeout(hide); };
  }, [active, reduced, manual, isIos]);
  useEffect(() => { panel.value = withTiming(open ? 1 : 0, { duration: reduced ? 0 : 220 }); }, [open, panel, reduced]);
  useEffect(() => () => { if (finish.current) clearTimeout(finish.current); }, []);
  useEffect(() => {
    if (!active && finish.current) { clearTimeout(finish.current); finish.current = null; setPhase('idle'); }
  }, [active]);
  const glow = useAnimatedStyle(() => ({ opacity: open ? 0 : interpolate(progress.value, [0, 0.5, 1], [0.1, 0.3, 0.1]), transform: [{ scale: reduced ? 1 : 1 + Math.sin(progress.value * Math.PI) * 0.3 }] }));
  const panelStyle = useAnimatedStyle(() => ({ opacity: panel.value, transform: [{ translateY: (1 - panel.value) * 12 }, { scale: 0.95 + panel.value * 0.05 }] }));
  const demo = () => {
    if (finish.current) return;
    setManual(true); setPhase('working');
    finish.current = setTimeout(() => { setPhase('ready'); finish.current = null; }, reduced ? 100 : 1100);
  };
  return <View style={{ width: 340, height: 396, alignItems: 'center' }}>
    <View style={{ width: 250, height: 380, borderRadius: 38, padding: 8, borderWidth: 1, borderColor: isDark ? '#63718C' : '#A9B9D2', backgroundColor: isDark ? '#17202F' : '#FFFFFF', boxShadow: '0 18px 35px rgba(18,46,107,0.16)' }}>
      <View style={{ flex: 1, borderRadius: 30, overflow: 'hidden', backgroundColor: colors.surfaceRaised }}>
        <View style={{ width: 65, height: 15, alignSelf: 'center', borderBottomLeftRadius: 12, borderBottomRightRadius: 12, backgroundColor: isDark ? '#17202F' : '#FFFFFF' }} />
        <View style={{ marginHorizontal: 15, marginTop: 16, flexDirection: 'row', alignItems: 'center', gap: 8 }}><View style={{ width: 26, height: 26, borderRadius: 13, backgroundColor: colors.border }} /><Text style={{ color: colors.text, fontSize: 13, fontWeight: '600' }}>{t('onboardingMessage')}</Text></View>
        <View style={{ margin: 15, width: 125, height: 45, backgroundColor: colors.surface, borderRadius: 16 }} />
        <View style={{ alignSelf: 'flex-end', marginEnd: 15, width: 135, height: 80, backgroundColor: isDark ? '#233D66' : '#D8E7FC', borderRadius: 18, justifyContent: 'center' }}><Shoe width={135} height={90} /></View>
        <View style={{ position: 'absolute', bottom: 18, left: 12, right: 12, height: 37, borderRadius: 19, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border }} />
        {isIos ? <View style={{ position: 'absolute', bottom: 72, alignSelf: 'center' }}><Label blue>{t('onboardingPng')}</Label></View> : null}
      </View>
    </View>
    {!isIos ? <>
      <Animated.View pointerEvents="none" style={[{ position: 'absolute', right: 0, top: 110, width: 110, height: 110, borderRadius: 55, backgroundColor: colors.primary }, glow]} />
      <Pressable accessibilityRole="button" accessibilityLabel={t('onboardingTapBubble')} accessibilityState={{ expanded: open }} hitSlop={8}
        onPress={() => { setManual(true); setOpen(!open); }} style={{ position: 'absolute', right: 20, top: 135, width: 60, height: 60 }}>
        <BubbleGlyph size={60} reducedMotion={reduced} />
      </Pressable>
      <Animated.View pointerEvents={open ? 'auto' : 'none'} accessibilityElementsHidden={!open} importantForAccessibility={open ? 'auto' : 'no-hide-descendants'}
        style={[{ position: 'absolute', top: 202, left: 48, width: 225, padding: 10, borderRadius: 22, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface, boxShadow: '0 12px 25px rgba(10,20,40,0.2)' }, panelStyle]}>
        {phase === 'ready' ? <Pressable accessibilityRole="button" accessibilityLabel={t('another')} onPress={() => setPhase('idle')} style={{ alignItems: 'center' }}><Shoe width={180} height={94} /><Text style={{ color: colors.primary, fontSize: 13, fontWeight: '600', padding: 6 }}>{t('onboardingPng')}</Text></Pressable> : phase === 'working' ? <View accessibilityRole="progressbar" accessibilityLabel={t('processing')} style={{ minHeight: 120, justifyContent: 'center', alignItems: 'center' }}><BubbleGlyph size={40} /><Text style={{ color: colors.text, marginTop: 12, fontSize: 13 }}>{t('processing')}</Text></View> : <>
          {[t('fromClipboard'), t('gallery'), t('file')].map((label, i) => <Pressable key={i} accessibilityRole="button" onPress={demo} style={{ minHeight: 48, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 12, paddingHorizontal: 12, marginVertical: 2, borderRadius: 12, backgroundColor: i === 0 ? colors.primary : colors.surfaceRaised }}><Text style={{ flexShrink: 1, textAlign: 'center', color: i === 0 ? colors.onPrimary : colors.text, fontSize: 14, fontWeight: '600' }}>{label}</Text></Pressable>)}
        </>}
      </Animated.View>
      {!open ? <View pointerEvents="none" style={{ position: 'absolute', left: 5, bottom: 48, maxWidth: 150 }}><Label blue>{t('onboardingTapBubble')}</Label></View> : null}
    </> : null}
  </View>;
}
