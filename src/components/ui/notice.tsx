import { useEffect, useState } from 'react';
import { AccessibilityInfo, Text } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  message: string;
};

export function Notice({ message }: Props) {
  const { colors } = useTheme();
  const [reducedMotion, setReducedMotion] = useState(false);
  const opacity = useSharedValue(0);
  const shift = useSharedValue(8);
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReducedMotion(value); });
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    return () => { mounted = false; subscription.remove(); };
  }, []);
  useEffect(() => {
    if (!message) return;
    if (reducedMotion) {
      opacity.value = 1;
      shift.value = 0;
      return;
    }
    opacity.value = 0;
    shift.value = 8;
    opacity.value = withTiming(1, { duration: 180 });
    shift.value = withTiming(0, { duration: 180 });
  }, [message, opacity, reducedMotion, shift]);
  const style = useAnimatedStyle(() => ({
    opacity: opacity.value,
    transform: [{ translateY: shift.value }],
  }));
  if (!message) return null;
  return (
    <Animated.View style={[style, { backgroundColor: colors.surfaceRaised }]} className="mt-2 rounded-2xl px-3 py-2">
      <Text accessibilityRole="alert" className="text-sm font-semibold" style={{ color: colors.text }}>{message}</Text>
    </Animated.View>
  );
}
