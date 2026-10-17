import type { ReactNode } from 'react';
import { ScrollView, useWindowDimensions, View } from 'react-native';
import { contentMax, pagePadding } from '../../logic/layout';
import { SYSTEM_BAR_GAP, useSystemBars } from '../../logic/systemBars';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  children: ReactNode;
  scroll?: boolean;
};

export function Screen({ children, scroll = false }: Props) {
  const insets = useSystemBars();
  const { colors } = useTheme();
  const { width } = useWindowDimensions();
  const max = contentMax(width);
  const pad = pagePadding(width);
  const bars = {
    paddingTop: insets.top + SYSTEM_BAR_GAP,
    paddingBottom: insets.bottom + SYSTEM_BAR_GAP,
    paddingLeft: insets.left,
    paddingRight: insets.right,
  };
  const body = (
    <View style={{ width: '100%', maxWidth: max, alignSelf: 'center', paddingHorizontal: pad, flex: scroll ? undefined : 1 }}>
      {children}
    </View>
  );
  if (scroll) {
    return (
      <View className="flex-1" style={[bars, { backgroundColor: colors.background }]}>
        <ScrollView className="flex-1" contentContainerStyle={{ flexGrow: 1 }}>
          {body}
        </ScrollView>
      </View>
    );
  }
  return (
    <View className="flex-1" style={[bars, { backgroundColor: colors.background }]}>
      {body}
    </View>
  );
}
