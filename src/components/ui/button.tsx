import { Pressable, Text } from 'react-native';
import { cn } from './cn';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  tone?: 'primary' | 'ghost';
};

export function Button({ label, onPress, disabled = false, tone = 'primary' }: Props) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      accessibilityState={{ disabled }}
      onPress={onPress}
      className={cn(
        'min-h-12 items-center justify-center rounded-2xl px-4 py-3',
        tone === 'primary' ? '' : 'border',
        disabled && 'opacity-40',
      )} style={{ minHeight: 48, backgroundColor: tone === 'primary' ? colors.primary : colors.surface, borderColor: colors.border }}>
      <Text className="text-base font-semibold" style={{ color: tone === 'primary' ? colors.onPrimary : colors.text }}>
        {label}
      </Text>
    </Pressable>
  );
}
