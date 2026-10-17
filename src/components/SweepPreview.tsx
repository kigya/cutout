import { memo, useEffect, useState } from 'react';
import { AccessibilityInfo, AppState, View } from 'react-native';
import { Canvas, Image, LinearGradient, Rect, useImage, vec, type SkImage } from '@shopify/react-native-skia';
import { Easing, useDerivedValue, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';
import { previewFrame } from '../logic/preview';
import { useTheme } from '../theme/ThemeProvider';

type Props = {
  path: string | null;
  overlayVisible?: boolean;
  width?: number;
  height?: number;
  windowWidth?: number;
  windowHeight?: number;
};

export const SweepPreview = memo(function SweepPreview({ path, overlayVisible = false, width, height, windowWidth = 0, windowHeight = 0 }: Props) {
  const image = useImage(path ? `file://${path}` : null);
  if (!image || image.width() <= 0 || image.height() <= 0) return <View className="mt-6" />;
  const frame = width && height
    ? { width, height }
    : previewFrame(windowWidth, windowHeight, image.width(), image.height());
  return <SweepFrame overlayVisible={overlayVisible} image={image} width={frame.width} height={frame.height} />;
});

function SweepFrame({ image, width, height, overlayVisible }: { image: SkImage; width: number; height: number; overlayVisible: boolean }) {
  const { colors } = useTheme();
  const clock = useSharedValue(0);
  const [reducedMotion, setReducedMotion] = useState(false);
  const [active, setActive] = useState(AppState.currentState === 'active');
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReducedMotion(value); });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    const appState = AppState.addEventListener('change', next => setActive(next === 'active'));
    return () => { mounted = false; motion.remove(); appState.remove(); };
  }, []);
  useEffect(() => {
    if (reducedMotion || (!active && !overlayVisible)) {
      clock.value = 0;
      return;
    }
    clock.value = withRepeat(withTiming(1, { duration: 1800, easing: Easing.linear }), -1, false);
    return () => { clock.value = 0; };
  }, [active, clock, overlayVisible, reducedMotion]);
  const band = width * 0.42;
  const start = useDerivedValue(() => vec(-band + clock.value * (width + band), 0));
  const end = useDerivedValue(() => vec(clock.value * (width + band), 0));
  return (
    <View className="mt-6 overflow-hidden rounded-3xl" style={{ width, height, backgroundColor: colors.surfaceRaised }}>
      <Canvas style={{ width, height }}>
        <Image image={image} x={0} y={0} width={width} height={height} fit="contain" />
        <Rect x={0} y={0} width={width} height={height}>
          <LinearGradient
            start={start}
            end={end}
            colors={['rgba(37,99,235,0)', 'rgba(96,165,250,0.8)', 'rgba(37,99,235,0)']}
          />
        </Rect>
      </Canvas>
    </View>
  );
}
