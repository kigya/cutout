import { createContext, type ReactNode, useContext } from 'react';
import { StatusBar, useColorScheme } from 'react-native';

export type ThemeMode = 'system' | 'light' | 'dark';

type Palette = {
  background: string; surface: string; surfaceRaised: string; text: string;
  textMuted: string; border: string; icon: string; primary: string;
  onPrimary: string; accent: string; warning: string; scrim: string;
};

const palettes: Record<'light' | 'dark', Palette> = {
  light: {
    background: '#F5F6F8',
    surface: '#FFFFFF',
    surfaceRaised: '#EEF0F4',
    text: '#17191F',
    textMuted: '#5F6673',
    border: '#D9DDE5',
    icon: '#303642',
    primary: '#2259CF',
    onPrimary: '#FFFFFF',
    accent: '#2458B8',
    warning: '#8A5100',
    scrim: 'rgba(10, 15, 24, 0.45)',
  },
  dark: {
    background: '#101114',
    surface: '#1B1D22',
    surfaceRaised: '#272A31',
    text: '#F5F6F8',
    textMuted: '#A5AAB5',
    border: '#363A44',
    icon: '#E5E7EB',
    primary: '#386BE0',
    onPrimary: '#FFFFFF',
    accent: '#8DB1FF',
    warning: '#FFD08A',
    scrim: 'rgba(0, 0, 0, 0.65)',
  },
} as const;

type Theme = { mode: ThemeMode; isDark: boolean; colors: Palette };
const ThemeContext = createContext<Theme>({ mode: 'system', isDark: true, colors: palettes.dark });

export function ThemeProvider({ mode = 'system', statusBar = true, children }: { mode?: ThemeMode; statusBar?: boolean; children: ReactNode }) {
  const systemScheme = useColorScheme();
  const isDark = mode === 'dark' || (mode === 'system' && systemScheme !== 'light');
  return (
    <ThemeContext.Provider value={{ mode, isDark, colors: isDark ? palettes.dark : palettes.light }}>
      {statusBar ? (
        <StatusBar barStyle={isDark ? 'light-content' : 'dark-content'} />
      ) : null}
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  return useContext(ThemeContext);
}
