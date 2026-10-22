import { initialWindowMetrics, useSafeAreaInsets, type EdgeInsets } from 'react-native-safe-area-context';

const EMPTY: EdgeInsets = { top: 0, right: 0, bottom: 0, left: 0 };

/** Space between a system bar and the first tappable control. */
export const SYSTEM_BAR_GAP = 12;

export function mergeInsets(live: EdgeInsets, initial: EdgeInsets | null): EdgeInsets {
  const base = initial ?? EMPTY;
  return {
    top: Math.max(live.top, base.top),
    right: Math.max(live.right, base.right),
    bottom: Math.max(live.bottom, base.bottom),
    left: Math.max(live.left, base.left),
  };
}

export function useSystemBars(): EdgeInsets {
  const live = useSafeAreaInsets();
  return mergeInsets(live, initialWindowMetrics?.insets ?? null);
}
