import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { DeviceEventEmitter, I18nManager } from 'react-native';
import { text } from './catalogs';
import type { MessageKey } from './messages/en';
import { isRtl, resolveLocale, type LocaleCode } from '../logic/languages';
import { Erase } from '../native/Erase';

type LocaleValue = {
  locale: LocaleCode;
  selected: string;
  t: (key: MessageKey) => string;
};

const LocaleContext = createContext<LocaleValue>({
  locale: 'en',
  selected: '',
  t: key => text('en', key),
});

export function LocaleGate({ children }: { children: ReactNode }) {
  const [locale, setLocale] = useState<LocaleCode>('en');
  const [selected, setSelected] = useState('');

  useEffect(() => {
    let alive = true;
    const apply = (nextSelected: string, system: string) => {
      if (!alive) return;
      const effective = nextSelected ? resolveLocale(nextSelected) : resolveLocale(system);
      setSelected(nextSelected);
      setLocale(effective);
      const rtl = isRtl(effective);
      if (I18nManager.isRTL !== rtl) {
        I18nManager.allowRTL(true);
        I18nManager.forceRTL(rtl);
        void Erase.restart();
      }
    };
    void Erase.getLocale().then(state => apply(state.selected, state.system)).catch(() => undefined);
    const subscription = DeviceEventEmitter.addListener('eraseLocale', event => {
      const payload = event as { selected?: string; system?: string };
      apply(payload.selected ?? '', payload.system ?? '');
    });
    return () => {
      alive = false;
      subscription.remove();
    };
  }, []);

  const value = useMemo<LocaleValue>(
    () => ({ locale, selected, t: key => text(locale, key) }),
    [locale, selected],
  );

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale(): LocaleValue {
  return useContext(LocaleContext);
}
