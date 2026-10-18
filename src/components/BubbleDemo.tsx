import { MainAppState as AppState } from '../native/MainAppState';
import { useEffect, useState } from 'react';
import { AccessibilityInfo, Pressable, Text, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';
import { BubbleGlyph } from './BubbleGlyph';
import { useLocale } from '../i18n/LocaleGate';
import { useTheme } from '../theme/ThemeProvider';

export function BubbleDemo({ onDismiss }: { onDismiss: () => void }) {
  const { t } = useLocale();
  const { colors } = useTheme();
  const [reducedMotion, setReducedMotion] = useState(false);
  const [active, setActive] = useState(AppState.currentState === 'active');
  const drift = useSharedValue(0);

  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReducedMotion(value); });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    const appState = AppState.addEventListener('change', next => setActive(next === 'active'));
    return () => { mounted = false; motion.remove(); appState.remove(); };
  }, []);

  useEffect(() => {
    if (reducedMotion || !active) {
      drift.value = 0;
      return;
    }
    drift.value = withRepeat(withTiming(1, { duration: 1700 }), -1, true);
    return () => { drift.value = 0; };
  }, [active, drift, reducedMotion]);

  const bubbleStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: drift.value * -5 }, { translateX: drift.value * -4 }],
  }));

  return (
    <View className="mt-3 flex-row items-center overflow-hidden rounded-3xl border p-3" style={{ backgroundColor: colors.surface, borderColor: colors.border }}>
      <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" className="h-16 flex-1 justify-center overflow-hidden rounded-2xl px-3" style={{ backgroundColor: colors.surfaceRaised }}>
        <View className="h-2 w-3/4 rounded-full opacity-35" style={{ backgroundColor: colors.textMuted }} />
        <View className="mt-2 h-2 w-1/2 rounded-full opacity-25" style={{ backgroundColor: colors.textMuted }} />
        <Animated.View className="absolute right-4 h-10 w-10 items-center justify-center" style={bubbleStyle}>
          <BubbleGlyph size={36} reducedMotion={reducedMotion} />
        </Animated.View>
      </View>
      <View className="ms-3 flex-1">
        <Text className="text-sm leading-5" style={{ color: colors.textMuted }}>{t('bubbleOptional')}</Text>
      </View>
      <Pressable accessibilityRole="button" accessibilityLabel={t('gotIt')} className="ms-1 h-12 w-12 items-center justify-center rounded-full" style={{ backgroundColor: colors.surfaceRaised }} onPress={onDismiss}>
        <Text className="text-lg" style={{ color: colors.icon }}>×</Text>
      </Pressable>
    </View>
  );
}
