import 'react-native-gesture-handler';
import './global.css';
import { AppRegistry, DeviceEventEmitter } from 'react-native';
import { useEffect, useRef, useState } from 'react';
import App from './src/App';
import { LocaleGate } from './src/i18n/LocaleGate';
import { BubbleApp } from './src/bubble/BubbleApp';
import { ThemeProvider } from './src/theme/ThemeProvider';
import { DEFAULT_PREFERENCES, readPreferences } from './src/logic/telemetry';

function BubbleRoot() {
  const [preferences, setPreferences] = useState(DEFAULT_PREFERENCES);
  const receivedUpdate = useRef(false);
  useEffect(() => {
    let mounted = true;
    const updates = DeviceEventEmitter.addListener('preferencesChanged', value => {
      receivedUpdate.current = true;
      setPreferences(normalizePreferences(value));
    });
    void readPreferences().then(saved => {
      if (mounted && !receivedUpdate.current) setPreferences(saved);
    });
    return () => {
      mounted = false;
      updates.remove();
    };
  }, []);
  return (
    <ThemeProvider mode={preferences.theme} statusBar={false}>
      <LocaleGate>
        <BubbleApp />
      </LocaleGate>
    </ThemeProvider>
  );
}

function normalizePreferences(value) {
  return {
    theme: ['system', 'light', 'dark'].includes(value?.theme) ? value.theme : 'system',
    analytics: value?.analytics === true,
    crashes: value?.crashes === true,
  };
}

AppRegistry.registerComponent('Erase', () => App);
AppRegistry.registerComponent('Bubble', () => BubbleRoot);
