import type { ReactNode } from 'react';
import { useEffect, useRef } from 'react';
import { Keyboard, Platform, ScrollView, useWindowDimensions, View } from 'react-native';
import type { ComponentRef } from 'react';
import { contentMax, pagePadding } from '../../logic/layout';
import { SYSTEM_BAR_GAP, useSystemBars } from '../../logic/systemBars';
import { useTheme } from '../../theme/ThemeProvider';

type Props = {
  children: ReactNode;
  scroll?: boolean;
  fullWidth?: boolean;
};

export function createFocusVisibilityController(
  scrollToKeyboard: (target: number) => void,
  subscribeKeyboardDidShow: (listener: () => void) => { remove: () => void } = listener => Keyboard.addListener('keyboardDidShow', listener),
) {
  let focusedTarget: number | undefined;
  const subscription = subscribeKeyboardDidShow(() => {
    if (focusedTarget !== undefined) scrollToKeyboard(focusedTarget);
  });
  return {
    focus(target: number) {
      focusedTarget = target;
      scrollToKeyboard(target);
    },
    blur(target: number) {
      if (focusedTarget === target) focusedTarget = undefined;
    },
    dispose() {
      focusedTarget = undefined;
      subscription.remove();
    },
  };
}

export function Screen({ children, scroll = false, fullWidth = false }: Props) {
  const insets = useSystemBars();
  const { colors } = useTheme();
  const { width } = useWindowDimensions();
  const scrollRef = useRef<ComponentRef<typeof ScrollView> | null>(null);
  const focusVisibilityRef = useRef<ReturnType<typeof createFocusVisibilityController> | null>(null);
  useEffect(() => {
    if (!scroll) return undefined;
    const controller = createFocusVisibilityController(target => {
      scrollRef.current?.scrollResponderScrollNativeHandleToKeyboard(target, 24, true);
    });
    focusVisibilityRef.current = controller;
    return () => {
      controller.dispose();
      focusVisibilityRef.current = null;
    };
  }, [scroll]);
  const max = fullWidth ? undefined : contentMax(width);
  const pad = pagePadding(width);
  const bars = {
    paddingTop: insets.top + SYSTEM_BAR_GAP,
    paddingBottom: insets.bottom + SYSTEM_BAR_GAP,
    paddingLeft: insets.left,
    paddingRight: insets.right,
  };
  const keepFocusedInputVisible = (event: { nativeEvent: { target: number } }) => {
    focusVisibilityRef.current?.focus(event.nativeEvent.target);
  };
  const clearFocusedInput = (event: { nativeEvent: { target: number } }) => {
    focusVisibilityRef.current?.blur(event.nativeEvent.target);
  };
  const body = (
    <View style={{ width: '100%', maxWidth: max, alignSelf: 'center', paddingHorizontal: pad, flex: scroll ? undefined : 1 }}>
      {children}
    </View>
  );
  if (scroll) {
    return (
      <View className="flex-1" style={[bars, { backgroundColor: colors.background }]}>
        <ScrollView ref={scrollRef} onFocus={keepFocusedInputVisible} onBlur={clearFocusedInput} testID="screen-scroll" className="flex-1" automaticallyAdjustKeyboardInsets={Platform.OS === 'ios'} keyboardDismissMode="on-drag" keyboardShouldPersistTaps="handled" contentContainerStyle={{ flexGrow: 1 }}>
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
