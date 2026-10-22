import { Image } from 'react-native';
import Animated, { useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { useTheme } from '../theme/ThemeProvider';

/** Shared artwork for the system overlay, onboarding and appearance preview. */
export function BubbleGlyph({ size = 64, opacity = 1, compact = false, dot = false, reducedMotion = false }: {
  size?: number; opacity?: number; compact?: boolean; dot?: boolean; reducedMotion?: boolean;
}) {
  const { colors } = useTheme();
  const visual = compact ? (dot ? 12 : 32) : size;
  const shape = useAnimatedStyle(() => ({
    transform: [{ scale: withTiming(visual / size, { duration: reducedMotion ? 0 : 180 }) }],
    borderRadius: withTiming(compact && dot ? size / 2 : size * 0.3, { duration: reducedMotion ? 0 : 180 }),
  }), [visual, size, compact, dot, reducedMotion]);
  return <Animated.View testID="bubble-artwork" pointerEvents="none" style={[{
    width: size, height: size, backgroundColor: colors.primary, opacity,
    alignItems: 'center', justifyContent: 'center', borderCurve: 'continuous',
    boxShadow: '0 3px 10px rgba(15, 44, 108, 0.18)',
  }, shape]}>
    <Image source={require('../assets/bubble-icon.png')} resizeMode="contain" style={{
      width: size * 0.63, height: size * 0.63, tintColor: colors.onPrimary,
      opacity: compact && dot ? 0 : 1,
    }} />
  </Animated.View>;
}
