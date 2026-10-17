import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, Text, useWindowDimensions, View } from 'react-native';
import { GestureDetector } from 'react-native-gesture-handler';
import { useDerivedValue, useSharedValue } from 'react-native-reanimated';
import {
  AlphaType,
  Canvas,
  Circle,
  ColorType,
  Fill,
  Group,
  Image,
  Shader,
  Skia,
  type SkImage,
  type SkPath,
  useImage,
} from '@shopify/react-native-skia';
import { BackButton } from '../components/ui/backButton';
import { Button } from '../components/ui/button';
import { Notice } from '../components/ui/notice';
import { Screen } from '../components/ui/screen';
import { useLocale } from '../i18n/LocaleGate';
import { useTheme } from '../theme/ThemeProvider';
import { base64ToBytes, bytesToBase64 } from '../logic/bytes';
import { windowTier } from '../logic/layout';
import { savePhase } from '../logic/save';
import {
  applyStrokeInPlace,
  canCopyEdited,
  sameAlpha,
  strokePoints,
  viewToBitmap,
  type BrushTool,
  type Point,
} from '../logic/stroke';
import { appendStroke, replayStrokes, type MaskStroke, type StrokeHistory } from '../editor/history';
import { useEditorGestureController } from '../editor/useEditorGestureController';
import { Erase } from '../native/Erase';
import { track } from '../logic/telemetry';

type Props = {
  originalPath: string;
  cutoutPath: string;
  width: number;
  height: number;
  note?: string;
  onBack: () => void;
};

const SIZES = [18, 36, 64];
const CHECKER = `
half4 main(float2 pos) {
  float2 cell = floor(pos / 16.0);
  float odd = mod(cell.x + cell.y, 2.0);
  return odd < 0.5 ? half4(0.247, 0.247, 0.275, 1.0) : half4(0.153, 0.153, 0.165, 1.0);
}
`;

function createMaskImage(alpha: Uint8Array, sourceAlpha: Uint8Array, width: number, height: number): SkImage | null {
  if (width <= 0 || height <= 0 || alpha.length !== width * height || sourceAlpha.length !== alpha.length) return null;
  const coverage = new Uint8Array(alpha.length);
  for (let index = 0; index < alpha.length; index += 1) {
    const original = sourceAlpha[index];
    coverage[index] = original === 0 ? 0 : Math.round(Math.min(alpha[index], original) * 255 / original);
  }
  return Skia.Image.MakeImage(
    { width, height, colorType: ColorType.Alpha_8, alphaType: AlphaType.Premul },
    Skia.Data.fromBytes(coverage),
    width,
  );
}

export function EditorScreen({ originalPath, cutoutPath, width, height, note, onBack }: Props) {
  const { t } = useLocale();
  const { colors } = useTheme();
  const checker = useMemo(() => Skia.RuntimeEffect.Make(CHECKER), []);
  const window = useWindowDimensions();
  const wide = windowTier(window.width) === 'expanded';
  const [tool, setTool] = useState<BrushTool>('erase');
  const [radius, setRadius] = useState(36);
  const [message, setMessage] = useState(note ?? '');
  const [ready, setReady] = useState(false);
  const [box, setBox] = useState({ width: 1, height: 1 });
  const [maskImage, setMaskImage] = useState<SkImage | null>(null);
  const [comparing, setComparing] = useState(false);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [historyState, setHistoryState] = useState({ undo: false, redo: false });
  const alphaRef = useRef<Uint8Array | null>(null);
  const sourceAlpha = useRef<Uint8Array | null>(null);
  const strokeHistory = useRef<StrokeHistory | null>(null);
  const points = useRef<Point[]>([]);
  const activeAlpha = useRef<Uint8Array | null>(null);
  const frame = useRef(false);
  const activeStroke = useRef(false);
  const activeTool = useRef(tool);
  const activeRadius = useRef(radius);
  const toolRef = useRef(tool);
  const radiusRef = useRef(radius);
  const boxRef = useRef(box);
  toolRef.current = tool;
  radiusRef.current = radius;
  boxRef.current = box;

  const original = useImage(`file://${originalPath}`);
  const boxW = useSharedValue(1);
  const boxH = useSharedValue(1);
  const scale = useSharedValue(1);
  const panX = useSharedValue(0);
  const panY = useSharedValue(0);
  const cursorX = useSharedValue(-80);
  const cursorY = useSharedValue(-80);
  const cursorR = useSharedValue(36);
  const brushRadius = useSharedValue(radius);
  brushRadius.value = radius;
  const transform = useDerivedValue(() => [
    { translateX: panX.value },
    { translateY: panY.value },
    { scale: scale.value },
  ]);
  const origin = useMemo(() => ({ x: box.width / 2, y: box.height / 2 }), [box.height, box.width]);

  useEffect(() => {
    let alive = true;
    setReady(false);
    void Erase.readAlpha(cutoutPath)
      .then(encoded => {
        if (!alive) return;
        const alpha = base64ToBytes(encoded);
        if (alpha.length !== width * height) throw new Error('Cutout mask dimensions do not match');
        alphaRef.current = new Uint8Array(alpha);
        strokeHistory.current = { base: new Uint8Array(alpha), strokes: [], index: 0 };
        activeAlpha.current = null;
        setHistoryState({ undo: false, redo: false });
        const originalAlpha = sourceAlpha.current;
        if (originalAlpha) {
          const image = createMaskImage(alpha, originalAlpha, width, height);
          if (!image) throw new Error('Skia could not create the mask image');
          setMaskImage(image);
          setReady(true);
        }
      })
      .catch(() => {
        if (alive) setMessage(t('saveFailed'));
      });
    return () => {
      alive = false;
    };
  }, [cutoutPath, height, t, width]);

  useEffect(() => {
    if (!original) return;
    const pixels = original.readPixels(0, 0, {
      width,
      height,
      colorType: ColorType.RGBA_8888,
      alphaType: AlphaType.Unpremul,
    });
    if (!pixels || pixels.length !== width * height * 4) {
      setMessage(t('saveFailed'));
      return;
    }
    const alpha = new Uint8Array(width * height);
    for (let pixel = 0; pixel < alpha.length; pixel += 1) alpha[pixel] = pixels[pixel * 4 + 3];
    sourceAlpha.current = alpha;
    const current = alphaRef.current;
    if (current) {
      const image = createMaskImage(current, alpha, width, height);
      if (!image) {
        setMessage(t('saveFailed'));
        return;
      }
      setMaskImage(image);
      setReady(true);
    }
  }, [height, original, t, width]);

  function syncHistory() {
    const history = strokeHistory.current;
    setHistoryState({ undo: !!history && history.index > 0, redo: !!history && history.index < history.strokes.length });
  }

  const showMask = useCallback((next: Uint8Array) => {
    const originalAlpha = sourceAlpha.current;
    if (!originalAlpha) return false;
    const image = createMaskImage(next, originalAlpha, width, height);
    if (!image) {
      setMessage(t('saveFailed'));
      return false;
    }
    alphaRef.current = next;
    setMaskImage(image);
    return true;
  }, [height, t, width]);

  function beginStroke(x: number, y: number, userScale: number, tx: number, ty: number) {
    if (!ready || !alphaRef.current) return;
    activeStroke.current = true;
    activeTool.current = toolRef.current;
    activeRadius.current = radiusRef.current;
    const current = boxRef.current;
    const first = viewToBitmap(x, y, current.width, current.height, width, height, { scale: userScale, tx, ty });
    points.current = [first];
    const fit = Math.min(current.width / width, current.height / height);
    const bitmapRadius = activeRadius.current / Math.max(fit * userScale, 0.01);
    activeAlpha.current = new Uint8Array(alphaRef.current);
    applyStrokeInPlace(activeAlpha.current, width, height, [first], bitmapRadius, activeTool.current);
    queueActiveMask();
  }

  function moveStroke(x: number, y: number, userScale: number, tx: number, ty: number) {
    if (!activeStroke.current || !activeAlpha.current) return;
    const current = boxRef.current;
    const next = viewToBitmap(x, y, current.width, current.height, width, height, { scale: userScale, tx, ty });
    const previous = points.current[points.current.length - 1] ?? next;
    const fit = Math.min(current.width / width, current.height / height);
    const userScaleSafe = userScale > 0 ? userScale : 1;
    const bitmapRadius = activeRadius.current / Math.max(fit * userScaleSafe, 0.01);
    const segment = strokePoints(previous, next, Math.max(1, bitmapRadius / 4));
    points.current.push(...segment);
    applyStrokeInPlace(activeAlpha.current, width, height, segment, bitmapRadius, activeTool.current);
    queueActiveMask();
  }

  function queueActiveMask() {
    if (!frame.current) {
      frame.current = true;
      requestAnimationFrame(() => {
        frame.current = false;
        const active = activeAlpha.current;
        const originalAlpha = sourceAlpha.current;
        if (activeStroke.current && active && originalAlpha) {
          const image = createMaskImage(active, originalAlpha, width, height);
          if (image) setMaskImage(image);
          else setMessage(t('saveFailed'));
        }
      });
    }
  }

  function cancelStroke() {
    activeStroke.current = false;
    points.current = [];
    activeAlpha.current = null;
    const committed = alphaRef.current;
    if (committed) showMask(committed);
    cursorX.value = -80;
  }

  function endStroke(userScale: number) {
    const current = alphaRef.current;
    const next = activeAlpha.current;
    const frameBox = boxRef.current;
    const sampled = points.current;
    activeStroke.current = false;
    activeAlpha.current = null;
    points.current = [];
    cursorX.value = -80;
    if (!current || !next || sampled.length === 0 || sameAlpha(next, current)) return;
    const fit = Math.min(frameBox.width / width, frameBox.height / height);
    const bitmapRadius = activeRadius.current / Math.max(fit * userScale, 0.01);
    const action = { points: sampled.map(point => ({ ...point })), radius: bitmapRadius, tool: activeTool.current };
    if (!showMask(next)) return;
    const history = strokeHistory.current;
    if (!history) return;
    strokeHistory.current = appendStroke(history, action, width, height);
    syncHistory();
    track('editor_action', { action: activeTool.current, surface: 'app' });
  }

  function replayTo(index: number) {
    const history = strokeHistory.current;
    if (!history || !showMask(replayStrokes(history.base, width, height, history.strokes.slice(0, index)))) return;
    strokeHistory.current = { ...history, index };
    syncHistory();
  }

  function undo() {
    track('editor_action', { action: 'undo', surface: 'app' });
    const history = strokeHistory.current;
    if (history && history.index > 0) replayTo(history.index - 1);
  }

  function redo() {
    track('editor_action', { action: 'redo', surface: 'app' });
    const history = strokeHistory.current;
    if (history && history.index < history.strokes.length) replayTo(history.index + 1);
  }

  async function exportCurrent(): Promise<string | null> {
    const current = alphaRef.current;
    if (!current || !canCopyEdited(current)) return null;
    const written = await Erase.writeMasked(originalPath, bytesToBase64(new Uint8Array(current)), width, height);
    return written.path ?? null;
  }

  async function copy() {
    try {
      const path = await exportCurrent();
      if (!path) {
        setMessage(t('nothingToCopy'));
        return;
      }
      const status = await Erase.publishPng(path);
      setMessage(status === 'copied' ? t('copied') : t('nothingToCopy'));
    } catch {
      setMessage(t('nothingToCopy'));
    }
  }

  async function save() {
    if (savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    try {
      const path = await exportCurrent();
      if (!path) {
        setMessage(t('saveFailed'));
        return;
      }
      const status = await Erase.saveToGallery(path);
      setMessage(t(savePhase(status) === 'saved' ? 'saved' : 'saveFailed'));
    } catch {
      setMessage(t('saveFailed'));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  const gesture = useEditorGestureController({
    boxWidth: boxW,
    boxHeight: boxH,
    scale,
    panX,
    panY,
    cursorX,
    cursorY,
    cursorRadius: cursorR,
    brushRadius,
    onDrawStart: beginStroke,
    onDrawMove: moveStroke,
    onDrawEnd: endStroke,
    onDrawCancel: cancelStroke,
  });
  const canvas = (
    <View
      className="flex-1 overflow-hidden rounded-3xl"
      style={{ backgroundColor: colors.surface }}
      onLayout={event => {
        const next = { width: event.nativeEvent.layout.width, height: event.nativeEvent.layout.height };
        if (next.width !== boxRef.current.width || next.height !== boxRef.current.height) {
          scale.value = 1;
          panX.value = 0;
          panY.value = 0;
        }
        boxW.value = next.width;
        boxH.value = next.height;
        boxRef.current = next;
        setBox(next);
      }}>
      <GestureDetector gesture={gesture}>
        <Canvas style={{ width: box.width, height: box.height }}>
          <Group transform={transform} origin={origin}>
            {checker ? <Fill><Shader source={checker} /></Fill> : <Fill color="#27272a" />}
          </Group>
          {comparing ? (
            <Group transform={transform} origin={origin}>
              {original ? <Image image={original} x={0} y={0} width={box.width} height={box.height} fit="contain" /> : null}
            </Group>
          ) : (
            <Group layer transform={transform} origin={origin}>
              {original && maskImage ? <Image image={original} x={0} y={0} width={box.width} height={box.height} fit="contain" /> : null}
              {maskImage ? <Image image={maskImage} x={0} y={0} width={box.width} height={box.height} fit="contain" blendMode="dstIn" /> : null}
            </Group>
          )}
          <Circle cx={cursorX} cy={cursorY} r={cursorR} color="rgba(255,255,255,0.8)" style="stroke" strokeWidth={2} />
        </Canvas>
      </GestureDetector>
    </View>
  );

  const tools = (
    <View className={wide ? 'w-56 justify-end gap-2 ps-4' : 'gap-2 pt-4'}>
      <View className="flex-row gap-2">
        <View className="flex-1"><Button label={t('erase')} tone={tool === 'erase' ? 'primary' : 'ghost'} onPress={() => setTool('erase')} /></View>
        <View className="flex-1"><Button label={t('restore')} tone={tool === 'restore' ? 'primary' : 'ghost'} onPress={() => setTool('restore')} /></View>
      </View>
      <View className="flex-row gap-2">
        <View className="flex-1"><Button label={t('undo')} tone="ghost" onPress={undo} disabled={!historyState.undo} /></View>
        <View className="flex-1"><Button label={t('redo')} tone="ghost" onPress={redo} disabled={!historyState.redo} /></View>
      </View>
      <View className="flex-row gap-2">
        <Pressable accessibilityRole="button" accessibilityLabel={t('compare')} className="h-12 flex-1 items-center justify-center rounded-2xl border" style={{ backgroundColor: colors.surface, borderColor: colors.border }} onPressIn={() => setComparing(true)} onPressOut={() => setComparing(false)}>
          <Text className="font-semibold" style={{ color: colors.text }}>{t('compare')}</Text>
        </Pressable>
        <Pressable accessibilityRole="button" accessibilityLabel={t('zoomFit')} className="h-12 items-center justify-center rounded-2xl border px-3" style={{ backgroundColor: colors.surface, borderColor: colors.border }} onPress={() => { scale.value = 1; panX.value = 0; panY.value = 0; track('editor_action', { action: 'fit', surface: 'app' }); }}>
          <Text className="font-semibold" style={{ color: colors.text }}>{t('zoomFit')}</Text>
        </Pressable>
      </View>
      <View className="flex-row justify-center gap-2">
        {SIZES.map(size => (
          <Pressable key={size} accessibilityRole="button" accessibilityLabel={`${t('erase')} ${size}`} className="h-12 w-12 items-center justify-center rounded-full" style={{ backgroundColor: colors.surface }} onPress={() => { setRadius(size); cursorR.value = size; }}>
            <View className="rounded-full" style={{ backgroundColor: colors.text, width: size / 4, height: size / 4, opacity: radius === size ? 1 : 0.4 }} />
          </Pressable>
        ))}
      </View>
    </View>
  );

  return (
    <Screen>
      <View className="flex-row items-center justify-between">
        <BackButton onPress={onBack} />
        <View className="flex-row gap-4">
          <Pressable accessibilityRole="button" accessibilityState={{ disabled: saving }} disabled={saving} className="h-12 justify-center" onPress={() => { void save(); }}>
            <Text className="text-base font-semibold" style={{ color: colors.text }}>{t('save')}</Text>
          </Pressable>
          <Pressable accessibilityRole="button" className="h-12 justify-center" onPress={() => { void copy(); }}>
            <Text className="text-base font-semibold" style={{ color: colors.accent }}>{t('copy')}</Text>
          </Pressable>
        </View>
      </View>
      <Text className="pb-3 text-sm" style={{ color: colors.textMuted }}>{ready ? t('editHint') : t('opening')}</Text>
      <Notice message={message} />
      <View className={wide ? 'min-h-0 flex-1 flex-row' : 'min-h-0 flex-1'}>
        {canvas}
        {tools}
      </View>
      <View className="h-2" />
    </Screen>
  );
}
