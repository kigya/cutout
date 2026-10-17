import { useRef, useState } from 'react';
import { AccessibilityInfo, Image, Text, View } from 'react-native';
import { BackButton } from '../components/ui/backButton';
import { Button } from '../components/ui/button';
import { Notice } from '../components/ui/notice';
import { Screen } from '../components/ui/screen';
import { useLocale } from '../i18n/LocaleGate';
import { localizeMessage } from '../i18n/catalogs';
import { savePhase } from '../logic/save';
import { canEdit, Erase, type EditSession } from '../native/Erase';
import { useTheme } from '../theme/ThemeProvider';

type Props = {
  session: EditSession;
  onBack: () => void;
  onEdit: () => void;
};

export function ResultScreen({ session, onBack, onEdit }: Props) {
  const { t, locale } = useLocale();
  const { colors } = useTheme();
  const [note, setNote] = useState(localizeMessage(locale, session.message));
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const path = session.cutoutPath;
  const editable = canEdit(session);

  async function copy() {
    if (!path || savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    setNote(t('copying'));
    try {
      const status = await Erase.publishPng(path);
      const message = status === 'copied' ? t('copied') : t('nothingToCopy');
      setNote(message);
      AccessibilityInfo.announceForAccessibility(message);
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  async function save() {
    if (savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    setNote(t('saving'));
    try {
      const status = await Erase.saveToGallery(path as string);
      const message = t(savePhase(status) === 'saved' ? 'saved' : 'saveFailed');
      setNote(message);
      AccessibilityInfo.announceForAccessibility(message);
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  return (
    <Screen>
      <BackButton onPress={onBack} />
      <Text accessibilityRole="header" className="mt-2 text-3xl font-semibold" style={{ color: colors.text }}>{t('done')}</Text>
      <Notice message={note} />
      {path ? (
        <Image accessibilityLabel={t('done')} source={{ uri: `file://${path}` }} className="mt-4 flex-1 rounded-3xl" style={{ backgroundColor: colors.surfaceRaised }} resizeMode="contain" />
      ) : (
        <View className="flex-1" />
      )}
      <View className="gap-2 pb-2 pt-4">
        <Button label={saving ? t('copying') : t('copy')} disabled={saving || !path} onPress={() => { void copy(); }} />
        <Button
          label={saving ? t('saving') : t('save')}
          tone="ghost"
          disabled={saving || !path || session.status === 'svg'}
          onPress={() => { void save(); }}
        />
        {editable ? <Button label={t('editInApp')} tone="ghost" onPress={onEdit} /> : null}
      </View>
    </Screen>
  );
}
