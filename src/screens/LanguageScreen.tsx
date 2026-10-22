import { FlatList, Pressable, Text, View } from 'react-native';
import { BackButton } from '../components/ui/backButton';
import { Screen } from '../components/ui/screen';
import { useLocale } from '../i18n/LocaleGate';
import { LANGUAGES, resolveLocale, sortLanguages } from '../logic/languages';
import { Erase } from '../native/Erase';
import { useTheme } from '../theme/ThemeProvider';

type Props = {
  onBack: () => void;
};

export function LanguageScreen({ onBack }: Props) {
  const { t, selected } = useLocale();
  const { colors } = useTheme();
  const languages = sortLanguages(LANGUAGES);

  async function choose(tag: string) {
    await Erase.setLocale(tag);
  }

  return (
    <Screen>
      <FlatList
        data={languages}
        keyExtractor={item => item.code}
        initialNumToRender={languages.length}
        style={{ flex: 1 }}
        ListHeaderComponent={
          <View>
            <BackButton onPress={onBack} />
            <Text accessibilityRole="header" className="mt-2 text-3xl font-semibold" style={{ color: colors.text }}>{t('languageTitle')}</Text>
            <Choice
              flag="🌐"
              label={t('matchSystem')}
              selected={selected === ''}
            colors={colors}
              onPress={() => { void choose(''); }}
            />
          </View>
        }
        renderItem={({ item }) => (
          <Choice
            flag={item.flag}
            label={item.nativeName}
            selected={selected !== '' && resolveLocale(selected) === item.code}
            colors={colors}
            onPress={() => { void choose(item.code); }}
          />
        )}
      />
    </Screen>
  );
}

function Choice({ flag, label, selected, onPress, colors }: { flag: string; label: string; selected: boolean; onPress: () => void; colors: ReturnType<typeof useTheme>['colors'] }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected }}
      className="mt-3 min-h-14 flex-row items-center rounded-3xl border px-4 py-3"
      style={{ backgroundColor: colors.surface, borderColor: selected ? colors.primary : colors.border }}
      onPress={onPress}>
      <Text className="text-2xl">{flag}</Text>
      <Text className="ms-3 flex-1 text-base font-semibold" style={{ color: colors.text }}>{label}</Text>
      {selected ? <View className="h-3 w-3 rounded-full" style={{ backgroundColor: colors.primary }} /> : null}
    </Pressable>
  );
}
