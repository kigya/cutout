import { useEffect, useRef, useState } from 'react';
import { Keyboard, Linking, Platform, Text, TextInput, View } from 'react-native';
import { BackButton } from '../components/ui/backButton';
import { Button } from '../components/ui/button';
import { Notice } from '../components/ui/notice';
import { Screen } from '../components/ui/screen';
import { useLocale } from '../i18n/LocaleGate';
import {
  createFeedbackAttempt,
  createFeedbackMailto,
  feedbackFingerprint,
  runFeedbackSubmissionOnce,
  submitFeedback,
  validateFeedback,
  type FeedbackAttempt,
  type FeedbackFailure,
  type FeedbackPlatform,
} from '../logic/feedback';
import { useTheme } from '../theme/ThemeProvider';

type Props = {
  endpoint: string;
  onBack: () => void;
  timeoutMs?: number;
};

export function FeedbackScreen({ endpoint, onBack, timeoutMs = 30_000 }: Props) {
  const { t } = useLocale();
  const { colors } = useTheme();
  const [email, setEmail] = useState('');
  const [message, setMessage] = useState('');
  const [notice, setNotice] = useState('');
  const [fieldErrors, setFieldErrors] = useState<{ email: boolean; message: boolean }>({ email: false, message: false });
  const [sending, setSending] = useState(false);
  const [showEmailFallback, setShowEmailFallback] = useState(false);
  const attemptRef = useRef<FeedbackAttempt | undefined>(undefined);
  const sendingRef = useRef(false);
  const mountedRef = useRef(false);
  const controllerRef = useRef<AbortController | undefined>(undefined);
  const sentFingerprintRef = useRef<string | undefined>(undefined);
  const platform: FeedbackPlatform = Platform.OS === 'ios' ? 'ios' : 'android';
  const currentFingerprint = feedbackFingerprint(email, message, platform);
  const alreadySent = sentFingerprintRef.current === currentFingerprint;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      controllerRef.current?.abort();
      controllerRef.current = undefined;
    };
  }, []);

  const updateEmail = (value: string) => {
    setEmail(value);
    setFieldErrors(errors => ({ ...errors, email: false }));
    setNotice('');
    setShowEmailFallback(false);
  };

  const updateMessage = (value: string) => {
    setMessage(value);
    setFieldErrors(errors => ({ ...errors, message: false }));
    setNotice('');
    setShowEmailFallback(false);
  };

  const send = async () => {
    const validation = validateFeedback(email, message);
    setFieldErrors({ email: !validation.email, message: !validation.message });
    setShowEmailFallback(false);
    if (!validation.email || !validation.message) {
      setNotice('');
      return;
    }

    await runFeedbackSubmissionOnce(sendingRef, async () => {
      const attempt = createFeedbackAttempt(email, message, platform, attemptRef.current);
      attemptRef.current = attempt;
      const controller = new AbortController();
      controllerRef.current = controller;
      setSending(true);
      setNotice('');
      try {
        await submitFeedback(endpoint, attempt.payload, fetch, timeoutMs, controller);
        if (mountedRef.current) {
          sentFingerprintRef.current = attempt.fingerprint;
          setNotice(t('feedbackSent'));
        }
      } catch (error) {
        if (mountedRef.current) {
          const failure: FeedbackFailure = error instanceof Error && error.message === 'rejected'
            ? 'rejected'
            : error instanceof Error && error.message === 'timeout'
              ? 'timeout'
              : error instanceof Error && error.message === 'response'
                ? 'response'
                : 'network';
          setNotice(failure === 'rejected' ? t('feedbackUnable') : t('feedbackDeliveryUncertain'));
          setShowEmailFallback(true);
        }
      } finally {
        if (controllerRef.current === controller) controllerRef.current = undefined;
        if (mountedRef.current) setSending(false);
      }
    });
  };

  const openEmail = () => {
    void Linking.openURL(createFeedbackMailto(email, message)).catch(() => setNotice(t('feedbackFailed')));
  };

  return (
    <Screen scroll>
      <BackButton onPress={onBack} />
      <Text className="mt-2 text-3xl font-semibold" style={{ color: colors.text }}>{t('feedback')}</Text>
      <Text className="mt-3 text-base leading-6" style={{ color: colors.textMuted }}>{t('feedbackDisclosure')}</Text>
      <Text className="mb-2 mt-6 text-base font-semibold" style={{ color: colors.text }}>{t('feedbackEmailLabel')}</Text>
      {/* Native text owns the caret; JS mirrors changes for validation without rewriting typed text. */}
      <TextInput
        accessibilityLabel={t('feedbackEmailLabel')}
        autoCapitalize="none"
        autoComplete="email"
        autoCorrect={false}
        editable={!sending}
        keyboardType="email-address"
        onChangeText={updateEmail}
        placeholder={t('feedbackEmailLabel')}
        placeholderTextColor={colors.textMuted}
        returnKeyType="done"
        submitBehavior="blurAndSubmit"
        onSubmitEditing={Keyboard.dismiss}
        style={{ minHeight: 52, borderRadius: 16, borderWidth: 1, borderColor: fieldErrors.email ? colors.warning : colors.border, backgroundColor: colors.surface, paddingHorizontal: 16, paddingVertical: 12, color: colors.text, fontSize: 16, textAlignVertical: 'center' }}
        textContentType="emailAddress"
        defaultValue=""
      />
      {fieldErrors.email ? <Text accessibilityRole="alert" className="mt-1 text-sm" style={{ color: colors.warning }}>{t('feedbackEmailInvalid')}</Text> : null}

      <Text className="mb-2 mt-5 text-base font-semibold" style={{ color: colors.text }}>{t('feedbackMessageLabel')}</Text>
      <TextInput
        accessibilityLabel={t('feedbackMessageLabel')}
        editable={!sending}
        maxLength={4000}
        multiline
        scrollEnabled
        returnKeyType="done"
        submitBehavior="blurAndSubmit"
        onSubmitEditing={Keyboard.dismiss}
        onChangeText={updateMessage}
        placeholder={t('feedbackMessageLabel')}
        placeholderTextColor={colors.textMuted}
        style={{ minHeight: 150, maxHeight: 220, borderRadius: 16, borderWidth: 1, borderColor: fieldErrors.message ? colors.warning : colors.border, backgroundColor: colors.surface, paddingHorizontal: 16, paddingVertical: 12, color: colors.text, fontSize: 16, textAlignVertical: 'top' }}
        defaultValue=""
      />
      <View className="mt-1 flex-row justify-between">
        {fieldErrors.message
          ? <Text accessibilityRole="alert" className="flex-1 pr-3 text-sm" style={{ color: colors.warning }}>{t('feedbackMessageInvalid')}</Text>
          : <View className="flex-1" />}
        <Text className="text-sm" style={{ color: colors.textMuted }}>{message.length}/4000</Text>
      </View>

      <Notice message={notice} />
      <View className="mt-4 gap-3">
        <Button label={sending ? t('feedbackSending') : alreadySent ? t('feedbackSent') : t('feedbackSend')} onPress={() => { void send(); }} disabled={sending || alreadySent} />
        {showEmailFallback ? <Button label={t('feedbackEmailFallback')} onPress={openEmail} tone="ghost" /> : null}
      </View>
    </Screen>
  );
}
