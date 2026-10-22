import { useEffect, useState, type ReactNode } from 'react';
import { AccessibilityInfo, Modal, Pressable, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { pagePadding } from '../../logic/layout';
import { SYSTEM_BAR_GAP, useSystemBars } from '../../logic/systemBars';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  title: string;
  body: string;
  visible: boolean;
  closeLabel: string;
  onClose: () => void;
  actionLabel?: string;
  onAction?: () => void;
  icon?: ReactNode;
};

export function Sheet({ title, body, visible, closeLabel, onClose, actionLabel, onAction, icon }: Props) {
  const insets = useSystemBars();
  const { colors } = useTheme();
  const [reducedMotion, setReducedMotion] = useState(false);
  const { width, height } = useWindowDimensions();
  const pad = pagePadding(width);
  const rise = useSharedValue(12);
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReducedMotion(value); });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReducedMotion);
    return () => { mounted = false; motion.remove(); };
  }, []);
  useEffect(() => {
    rise.value = visible && !reducedMotion ? withTiming(0, { duration: 160 }) : visible ? 0 : 12;
  }, [reducedMotion, rise, visible]);
  const sheetStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: rise.value }],
  }));
  return (
    <Modal animationType="none" transparent visible={visible} onRequestClose={onClose}>
      <Pressable accessible={false} className="flex-1 justify-end" style={{ backgroundColor: colors.scrim }} onPress={onClose}>
        <Animated.View style={sheetStyle}>
        <Pressable
          accessible={false}
          className="rounded-t-3xl border"
          style={{
            maxHeight: height - insets.top - SYSTEM_BAR_GAP,
            backgroundColor: colors.surface,
            borderColor: colors.border,
            paddingTop: 20,
            paddingBottom: insets.bottom + SYSTEM_BAR_GAP,
            paddingLeft: insets.left + pad,
            paddingRight: insets.right + pad,
          }}
          onPress={() => undefined}>
          <View className="flex-row items-center gap-3">
            {icon ? <View className="h-12 w-12 items-center justify-center rounded-2xl" style={{ backgroundColor: colors.surfaceRaised }}>{icon}</View> : null}
            <Text className="flex-1 text-lg font-semibold" style={{ color: colors.text }}>{title}</Text>
          </View>
          <ScrollView style={{ flexShrink: 1 }} contentContainerStyle={{ paddingBottom: 4 }}>
            <Text className="mt-3 text-base leading-6" style={{ color: colors.textMuted }}>{body}</Text>
          </ScrollView>
          {actionLabel && onAction ? <Pressable accessibilityRole="button" className="mt-5 min-h-12 items-center justify-center rounded-2xl px-4" style={{ backgroundColor: colors.primary }} onPress={onAction}><Text className="font-semibold" style={{ color: colors.onPrimary }}>{actionLabel}</Text></Pressable> : null}
          <Pressable accessibilityRole="button" className="mt-3 min-h-12 items-center justify-center rounded-2xl" style={{ backgroundColor: colors.surfaceRaised }} onPress={onClose}>
            <Text className="font-semibold" style={{ color: colors.text }}>{closeLabel}</Text>
          </Pressable>
        </Pressable>
        </Animated.View>
      </Pressable>
    </Modal>
  );
}
