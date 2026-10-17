import { Text, View } from 'react-native';
import { cn } from './cn';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  label: string;
  tone?: 'neutral' | 'good' | 'warn';
};

export function Badge({ label, tone = 'neutral' }: Props) {
  const { colors, isDark } = useTheme();
  const background = tone === 'good' ? (isDark ? '#123C2A' : '#DDF4E8') : tone === 'warn' ? (isDark ? '#472E0C' : '#FFF0D6') : colors.surfaceRaised;
  const foreground = tone === 'good' ? (isDark ? '#9AE6B4' : '#17673D') : tone === 'warn' ? (isDark ? '#FFD08A' : '#7A4800') : colors.textMuted;
  return (
    <View
      className={cn(
        'rounded-full px-2 py-1',
      )}
      style={{ backgroundColor: background }}>
      <Text
        className="text-xs" style={{ color: foreground }}>
        {label}
      </Text>
    </View>
  );
}
