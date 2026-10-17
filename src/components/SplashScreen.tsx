import { useEffect, useState } from 'react';
import { AccessibilityInfo, Image, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withDelay, withTiming } from 'react-native-reanimated';
import { useTheme } from '../theme/ThemeProvider';

type Props = {
  onDone: () => void;
};

export function SplashScreen({ onDone }: Props) {
  const { colors } = useTheme();
  const [reducedMotion, setReducedMotion] = useState(false);
  const icon = useSharedValue(0.86);
  const chip = useSharedValue(0);
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReducedMotion(value); });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    const timer = setTimeout(onDone, 1500);
    return () => { mounted = false; motion.remove(); clearTimeout(timer); };
  }, [onDone]);
  useEffect(() => {
    if (reducedMotion) {
      icon.value = 1;
      chip.value = 1;
      return;
    }
    icon.value = withTiming(1, { duration: 260 });
    chip.value = withDelay(100, withTiming(1, { duration: 400 }));
  }, [chip, icon, reducedMotion]);
  const iconStyle = useAnimatedStyle(() => ({
    opacity: icon.value,
    transform: [{ scale: icon.value }],
  }));
  const chipStyle = useAnimatedStyle(() => ({
    opacity: 1 - chip.value,
    transform: [{ translateX: chip.value * 36 }, { translateY: chip.value * -18 }],
  }));
  return (
    <View className="flex-1 items-center justify-center" style={{ backgroundColor: colors.background }}>
      <Animated.View style={iconStyle}>
        <Image source={require('../assets/bubble-icon.png')} style={{ width: 160, height: 160 }} resizeMode="contain" />
      </Animated.View>
      <Animated.View className="absolute h-4 w-4 rounded-md" style={[{ marginLeft: 72, marginTop: -24, backgroundColor: colors.accent }, chipStyle]} />
    </View>
  );
}
