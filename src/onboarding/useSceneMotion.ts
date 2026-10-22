import { useEffect, useState } from 'react';
import { AccessibilityInfo } from 'react-native';
import { cancelAnimation, Easing, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';
import { MainAppState } from '../native/MainAppState';

export function useSceneMotion(duration = 5200) {
  const [reduced, setReduced] = useState(true);
  const [active, setActive] = useState(MainAppState.currentState === 'active');
  const progress = useSharedValue(0.65);
  useEffect(() => {
    let live = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(next => { if (live) setReduced(next); });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduced);
    const app = MainAppState.addEventListener('change', next => setActive(next === 'active'));
    return () => { live = false; motion.remove(); app.remove(); };
  }, []);
  useEffect(() => {
    cancelAnimation(progress);
    if (reduced || !active) { progress.value = 0.65; return; }
    progress.value = 0;
    progress.value = withRepeat(withTiming(1, { duration, easing: Easing.linear }), -1, false);
    return () => cancelAnimation(progress);
  }, [active, reduced, duration, progress]);
  return { progress, reduced, active };
}
