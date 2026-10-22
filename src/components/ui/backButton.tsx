import { Pressable, View } from 'react-native';
import { useLocale } from '../../i18n/LocaleGate';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  onPress: () => void;
};

export function BackButton({ onPress }: Props) {
  const { t } = useLocale();
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={t('back')}
      className="h-12 w-12 items-center justify-center"
      hitSlop={4}
      onPress={onPress}>
      <View
        style={{
          borderColor: colors.icon,
          width: 12,
          height: 12,
          borderLeftWidth: 2.5,
          borderBottomWidth: 2.5,
          transform: [{ rotate: '45deg' }, { translateX: 2 }],
        }}
      />
    </Pressable>
  );
}
