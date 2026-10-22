import { memo, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, Image, Platform, Text, View } from 'react-native';
import { BackButton } from '../components/ui/backButton';
import { Button } from '../components/ui/button';
import { Notice } from '../components/ui/notice';
import { Screen } from '../components/ui/screen';
import { Sheet } from '../components/ui/sheet';
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
  const [photosDenied, setPhotosDenied] = useState(false);
  const [note, setNote] = useState(localizeMessage(locale, session.message));
  const [action, setAction] = useState<'copy' | 'save' | null>(null);
  const savingRef = useRef(false);
  const path = session.cutoutPath;
  const editable = canEdit(session);

  async function copy() {
    if (!path || savingRef.current) return;
    savingRef.current = true;
    setAction('copy');
    try {
      const status = await Erase.publishPng(path);
      const message = status === 'copied' ? t('copied') : t('nothingToCopy');
      setNote(message);
      AccessibilityInfo.announceForAccessibility(message);
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setAction(null);
    }
  }

  async function save() {
    if (savingRef.current) return;
    savingRef.current = true;
    setAction('save');
    try {
      const status = await Erase.saveToGallery(path as string);
      if (Platform.OS === 'ios' && status === 'denied') setPhotosDenied(true);
      const message = t(savePhase(status) === 'saved' ? 'saved' : 'saveFailed');
      setNote(message);
      AccessibilityInfo.announceForAccessibility(message);
    } catch {
      setNote(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setAction(null);
    }
  }

  return (
    <Screen>
      <BackButton onPress={onBack} />
      <Text accessibilityRole="header" className="mt-2 text-3xl font-semibold" style={{ color: colors.text }}>{t('done')}</Text>
      <Notice message={note} />
      {path ? (
        <ResultPreview path={path} label={t('done')} background={colors.surfaceRaised} />
      ) : (
        <View className="flex-1" />
      )}
      <View className="gap-2 pb-2 pt-4">
        <Button label={t('copy')} loading={action === 'copy'} disabled={action != null || !path} onPress={() => { void copy(); }} />
        <Button
          label={t('save')}
          loading={action === 'save'}
          tone="ghost"
          disabled={action != null || !path || session.status === 'svg'}
          onPress={() => { void save(); }}
        />
        {editable ? <Button label={t('editInApp')} tone="ghost" onPress={onEdit} /> : null}
      </View>
      <Sheet visible={photosDenied} title={t('photosPermissionTitle')} body={t('photosPermissionBody')} closeLabel={t('notNow')} onClose={() => setPhotosDenied(false)} actionLabel={t('openSettings')} onAction={() => {
        setPhotosDenied(false);
        void Erase.openPermission('photos').catch(() => setNote(t('saveFailed')));
      }} />
    </Screen>
  );
}

const ResultPreview = memo(function ResultPreview({ path, label, background }: { path: string; label: string; background: string }) {
  const source = useMemo(() => ({ uri: `file://${path}` }), [path]);
  return <Image accessibilityLabel={label} source={source} className="mt-4 flex-1 rounded-3xl" style={{ backgroundColor: background }} resizeMode="contain" />;
});
