import { View } from 'react-native';
import type { PermissionKind } from '../../logic/permissions';
import { useTheme } from '../../theme/ThemeProvider';

export function InfoIcon() {
  const { colors } = useTheme();
  return (
    <View className="h-6 w-6 items-center rounded-full border-2" style={{ borderColor: colors.icon }}>
      <View className="mt-1 h-1 w-1 rounded-full" style={{ backgroundColor: colors.icon }} />
      <View className="mt-0.5 h-2 w-0.5 rounded-full" style={{ backgroundColor: colors.icon }} />
    </View>
  );
}

export function GearIcon() {
  const { colors } = useTheme();
  return (
    <View className="h-5 w-5 items-center justify-center">
      <View className="absolute h-5 w-1 rounded-sm" style={{ backgroundColor: colors.icon }} />
      <View className="absolute h-1 w-5 rounded-sm" style={{ backgroundColor: colors.icon }} />
      <View className="absolute h-5 w-1 rounded-sm" style={{ backgroundColor: colors.icon, transform: [{ rotate: '45deg' }] }} />
      <View className="absolute h-5 w-1 rounded-sm" style={{ backgroundColor: colors.icon, transform: [{ rotate: '-45deg' }] }} />
      <View className="h-3.5 w-3.5 items-center justify-center rounded-full" style={{ backgroundColor: colors.icon }}>
        <View className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: colors.surface }} />
      </View>
    </View>
  );
}

export function RequiredMark() {
  return <View className="h-2 w-2 rounded-full bg-amber-300" />;
}

export function OptionalMark() {
  const { colors } = useTheme();
  return <View className="h-2 w-2 rounded-full border" style={{ borderColor: colors.textMuted }} />;
}

export function PermissionIcon({ kind }: { kind: PermissionKind }) {
  const { colors } = useTheme();
  switch (kind) {
    case 'overlay':
      return (
        <View className="h-6 w-6 items-center justify-center">
          <View className="absolute h-4 w-5 rounded-md border" style={{ transform: [{ translateY: -2 }], borderColor: colors.accent }} />
          <View className="h-4 w-5 rounded-md border" style={{ borderColor: colors.icon, backgroundColor: colors.surface }} />
        </View>
      );
    case 'backgroundPopup':
      return (
        <View className="h-6 w-6 items-center justify-center">
          <View className="h-4 w-5 rounded-md border" style={{ borderColor: colors.icon }} />
          <View className="absolute h-2 w-2 rounded-sm" style={{ transform: [{ translateX: 6 }, { translateY: 6 }], backgroundColor: colors.accent }} />
        </View>
      );
    case 'autostart':
      return (
        <View className="h-6 w-6 items-center justify-center rounded-full border-2" style={{ borderColor: colors.accent }}>
          <View className="h-2 w-2 rounded-sm" style={{ transform: [{ translateX: 3 }, { translateY: -3 }], backgroundColor: colors.icon }} />
        </View>
      );
    case 'battery':
      return (
        <View className="h-6 w-6 items-center justify-center">
          <View className="h-3 w-5 rounded-sm border" style={{ borderColor: colors.icon }}>
            <View className="m-0.5 h-2 w-2 rounded-sm" style={{ backgroundColor: colors.warning }} />
          </View>
          <View className="absolute h-1.5 w-0.5 rounded-full" style={{ transform: [{ translateX: 12 }], backgroundColor: colors.icon }} />
        </View>
      );
    case 'notifications':
      return (
        <View className="h-6 w-6 items-center justify-end">
          <View className="h-3.5 w-3.5 rounded-t-full border-2" style={{ borderColor: colors.icon }} />
          <View className="h-0.5 w-4 rounded-full" style={{ backgroundColor: colors.icon }} />
        </View>
      );
    /* istanbul ignore next -- PermissionKind has no remaining members */
    default: {
      const unknown: never = kind;
      return unknown;
    }
  }
}
