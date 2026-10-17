import { useEffect, useState } from 'react';
import { AccessibilityInfo, Image, StatusBar, View } from 'react-native';
import Animated, { Easing, useAnimatedStyle, useSharedValue, withDelay, withTiming } from 'react-native-reanimated';

type Props = { onDone: () => void };
export const SPLASH_BACKGROUND = '#2259CF';

/** A little cutout: the eraser follows the paper edge, scraps leave, the mark stays. */
export function SplashScreen({ onDone }: Props) {
  // Start static until the accessibility preference is known, avoiding one unwanted frame.
  const [reducedMotion, setReducedMotion] = useState(true);
  const trace = useSharedValue(0);
  const reveal = useSharedValue(0);
  const scraps = useSharedValue(0);
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReducedMotion(value); });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    const timer = setTimeout(onDone, 1500);
    return () => { mounted = false; motion.remove(); clearTimeout(timer); };
  }, [onDone]);
  useEffect(() => {
    if (reducedMotion) {
      trace.value = 1; reveal.value = 1; scraps.value = 1;
      return;
    }
    trace.value = 0; reveal.value = 0; scraps.value = 0;
    trace.value = withDelay(80, withTiming(1, { duration: 840, easing: Easing.linear }));
    reveal.value = withDelay(200, withTiming(1, { duration: 760 }));
    scraps.value = withDelay(780, withTiming(1, { duration: 440 }));
  }, [reducedMotion, reveal, scraps, trace]);
  const paperStyle = useAnimatedStyle(() => ({
    opacity: (1 - scraps.value) * 0.9,
    transform: [{ rotate: `${-6 + scraps.value * 9}deg` }, { scale: 1 - scraps.value * 0.08 }],
  }));
  const eraserStyle = useAnimatedStyle(() => ({
    opacity: trace.value < 0.98 ? 1 : (1 - trace.value) * 50,
    transform: [{ translateX: Math.sin(trace.value * Math.PI) * 86 - 20 }, { translateY: trace.value * 168 - 98 }, { rotate: `${trace.value * 40 - 25}deg` }],
  }));
  const markStyle = useAnimatedStyle(() => ({
    opacity: reducedMotion ? 1 : 0.35 + reveal.value * 0.65,
    transform: [{ scale: reducedMotion ? 1 : 0.96 + reveal.value * 0.04 }],
  }));
  const topScrap = useAnimatedStyle(() => ({
    opacity: 1 - scraps.value,
    transform: [{ translateX: scraps.value * -40 }, { translateY: scraps.value * -26 }, { rotate: `${scraps.value * -32}deg` }],
  }));
  const bottomScrap = useAnimatedStyle(() => ({
    opacity: 1 - scraps.value,
    transform: [{ translateX: scraps.value * 45 }, { translateY: scraps.value * 30 }, { rotate: `${scraps.value * 38}deg` }],
  }));
  return (
    <View testID="splash" className="flex-1 items-center justify-center" style={{ backgroundColor: SPLASH_BACKGROUND }}>
      <StatusBar barStyle="light-content" />
      <View accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ width: 224, height: 224, alignItems: 'center', justifyContent: 'center' }}>
        <Animated.View style={[{ position: 'absolute', width: 164, height: 184, borderRadius: 28, backgroundColor: '#7C9EE8', borderWidth: 2, borderColor: '#ADC6FF', borderStyle: 'dashed' }, paperStyle]} />
        <Animated.View style={markStyle}>
          <Image source={require('../assets/bubble-icon.png')} style={{ width: 160, height: 160, tintColor: '#FFFFFF' }} resizeMode="contain" />
        </Animated.View>
        <Animated.View style={[{ position: 'absolute', left: 22, top: 20, width: 22, height: 36, borderRadius: 5, backgroundColor: '#ADC6FF' }, topScrap]} />
        <Animated.View style={[{ position: 'absolute', right: 22, bottom: 24, width: 28, height: 24, borderRadius: 5, backgroundColor: '#7C9EE8' }, bottomScrap]} />
        <Animated.View style={[{ position: 'absolute', width: 28, height: 42, borderRadius: 7, backgroundColor: '#FFFFFF', borderBottomWidth: 14, borderColor: '#AAC6FF' }, eraserStyle]} />
      </View>
    </View>
  );
}
