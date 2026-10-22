import { useMemo, useRef, useState } from 'react';
import { I18nManager, PanResponder, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';

export function AppearanceSlider({ label, value, min, max, step, format, onChange, onCommit }: {
  label: string; value: number; min: number; max: number; step: number;
  format: (value: number) => string; onChange: (value: number) => void; onCommit: (value: number) => void;
}) {
  const { colors } = useTheme();
  const [width, setWidth] = useState(1);
  const latest = useRef({ value, width, onChange, onCommit });
  latest.current = { value, width, onChange, onCommit };
  const origin = useRef(0);
  const draft = useRef(value);
  const quantize = (next: number) => Math.max(min, Math.min(max, Math.round((next - min) / step) * step + min));
  const responder = useMemo(() => {
    const update = (pageX: number) => {
      let fraction = Math.max(0, Math.min(1, (pageX - origin.current - 12) / Math.max(1, latest.current.width - 24)));
      if (I18nManager.isRTL) fraction = 1 - fraction;
      draft.current = quantize(min + fraction * (max - min));
      latest.current.onChange(draft.current);
    };
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderGrant: event => {
        origin.current = event.nativeEvent.pageX - event.nativeEvent.locationX;
        update(event.nativeEvent.pageX);
      },
      onPanResponderMove: event => update(event.nativeEvent.pageX),
      onPanResponderRelease: () => latest.current.onCommit(draft.current),
      onPanResponderTerminate: () => latest.current.onCommit(draft.current),
      onPanResponderTerminationRequest: () => false,
    });
  }, [min, max, step]);
  const fraction = (value - min) / (max - min);
  const position = (I18nManager.isRTL ? 1 - fraction : fraction) * Math.max(0, width - 24) + 12;
  return <View accessible accessibilityRole="adjustable" accessibilityLabel={label}
    accessibilityValue={{ min, max, now: value, text: format(value) }}
    accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
    onAccessibilityAction={event => {
      const next = quantize(value + (event.nativeEvent.actionName === 'increment' ? step : -step));
      onChange(next); onCommit(next);
    }}
    onLayout={event => setWidth(event.nativeEvent.layout.width)}
    style={{ height: 56, justifyContent: 'center', direction: 'ltr' }} {...responder.panHandlers}>
    <View pointerEvents="none" style={{ marginHorizontal: 12, height: 10, borderRadius: 10, backgroundColor: colors.border, overflow: 'hidden' }}>
      <View style={{ height: 10, width: `${fraction * 100}%`, backgroundColor: colors.primary, alignSelf: I18nManager.isRTL ? 'flex-end' : 'flex-start' }} />
    </View>
    <View pointerEvents="none" style={{ position: 'absolute', left: position - 3, width: 6, height: 34, borderRadius: 3, backgroundColor: colors.primary, borderWidth: 2, borderColor: colors.surface, boxShadow: '0 1px 4px rgba(0,0,0,0.18)' }} />
  </View>;
}
