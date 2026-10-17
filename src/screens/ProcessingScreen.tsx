import { ActivityIndicator, Text, useWindowDimensions, View } from 'react-native';
import { BackButton } from '../components/ui/backButton';
import { Screen } from '../components/ui/screen';
import { SweepPreview } from '../components/SweepPreview';
import { useLocale } from '../i18n/LocaleGate';
import { useTheme } from '../theme/ThemeProvider';
import type { BubbleSnapshot } from '../native/Erase';
import { clampPercent } from '../logic/progress';

type Props = {
  previewPath: string | null;
  percent: number;
  stage?: BubbleSnapshot['stage'];
  onCancel: () => void;
};

export function ProcessingScreen({ previewPath, percent, stage, onCancel }: Props) {
  const { t } = useLocale();
  const { colors } = useTheme();
  const window = useWindowDimensions();
  const stageLabel = stage === 'preparing'
    ? t('processingPreparing')
    : stage === 'inference'
      ? t('processingInference')
      : stage === 'exporting'
        ? t('processingExporting')
        : t('cutting');
  const active = stage === 'inference';
  return (
    <Screen>
      <BackButton onPress={onCancel} />
      <View className="flex-1 items-center justify-center py-6">
        <Text accessibilityRole="header" className="text-xl font-semibold text-center" style={{ color: colors.text }}>{t('processing')}</Text>
        <View className="mt-3 min-h-12 flex-row items-center justify-center gap-3">
          {active ? <ActivityIndicator accessibilityLabel={stageLabel} color={colors.primary} size="small" /> : null}
          <Text accessibilityLiveRegion="polite" className="text-base font-medium" style={{ color: colors.textMuted }}>{stageLabel}</Text>
        </View>
        <Text accessibilityRole="progressbar" accessibilityLabel={t('processing')} accessibilityValue={{ min: 0, max: 100, now: clampPercent(percent) }} className="mb-3 text-lg font-semibold" style={{ color: colors.text }}>{clampPercent(percent)}%</Text>
        <SweepPreview path={previewPath} windowWidth={window.width} windowHeight={window.height} />
      </View>
    </Screen>
  );
}
