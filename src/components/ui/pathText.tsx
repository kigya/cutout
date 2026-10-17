import { Text, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  text: string;
};

export function PathText({ text }: Props) {
  const { colors } = useTheme();
  const parts = text.split(/(→|←)/);
  return (
    <Text accessibilityLabel={text} className="mt-2 text-sm leading-5" style={{ includeFontPadding: false, color: colors.textMuted }}>
      {parts.map((part, index) => {
        if (part !== '→' && part !== '←') return <Text key={index}>{part}</Text>;
        return (
          <View
            key={index}
            style={{
              width: 14,
              height: 14,
              justifyContent: 'center',
              alignItems: 'center',
              transform: [{ translateY: 3 }],
            }}>
            <View
              style={{
                width: 6,
                height: 6,
                borderTopWidth: 1.5,
                borderRightWidth: 1.5,
                borderColor: colors.accent,
                transform: [{ rotate: part === '←' ? '-135deg' : '45deg' }],
              }}
            />
          </View>
        );
      })}
    </Text>
  );
}
