import { Gesture } from 'react-native-gesture-handler';
import { runOnJS, useSharedValue, type SharedValue } from 'react-native-reanimated';
import { clampPan, clampScale, panForZoom } from '../logic/zoom';

type PointCallback = (x: number, y: number, scale: number, tx: number, ty: number) => void;

type Props = {
  boxWidth: SharedValue<number>;
  boxHeight: SharedValue<number>;
  scale: SharedValue<number>;
  panX: SharedValue<number>;
  panY: SharedValue<number>;
  cursorX: SharedValue<number>;
  cursorY: SharedValue<number>;
  cursorRadius: SharedValue<number>;
  brushRadius: SharedValue<number>;
  onDrawStart: PointCallback;
  onDrawMove: PointCallback;
  onDrawEnd: (scale: number) => void;
  onDrawCancel: () => void;
};

/**
 * App-specific adaptation of react-native-free-canvas 2.1.0's simultaneous
 * pan/pinch controller. It sends drawing samples to the alpha-mask editor and
 * keeps zoom, centroid panning, pointer cancellation, and clamping together.
 */
export function useEditorGestureController({
  boxWidth,
  boxHeight,
  scale,
  panX,
  panY,
  cursorX,
  cursorY,
  cursorRadius,
  brushRadius,
  onDrawStart,
  onDrawMove,
  onDrawEnd,
  onDrawCancel,
}: Props) {
  const savedScale = useSharedValue(1);
  const savedX = useSharedValue(0);
  const savedY = useSharedValue(0);
  const savedFocalX = useSharedValue(0);
  const savedFocalY = useSharedValue(0);
  const pinchChangingScale = useSharedValue(false);

  const draw = Gesture.Pan()
    .maxPointers(1)
    .onBegin(event => {
      cursorX.value = event.x;
      cursorY.value = event.y;
      cursorRadius.value = brushRadius.value;
      runOnJS(onDrawStart)(event.x, event.y, scale.value, panX.value, panY.value);
    })
    .onUpdate(event => {
      if (event.numberOfPointers > 1) {
        runOnJS(onDrawCancel)();
        return;
      }
      cursorX.value = event.x;
      cursorY.value = event.y;
      runOnJS(onDrawMove)(event.x, event.y, scale.value, panX.value, panY.value);
    })
    .onFinalize((event, success) => {
      cursorX.value = -80;
      if (success && event.numberOfPointers === 1) runOnJS(onDrawEnd)(scale.value);
      else runOnJS(onDrawCancel)();
    });

  const pinch = Gesture.Pinch()
    .onStart(event => {
      savedScale.value = scale.value;
      savedX.value = panX.value;
      savedY.value = panY.value;
      savedFocalX.value = event.focalX;
      savedFocalY.value = event.focalY;
      pinchChangingScale.value = false;
      runOnJS(onDrawCancel)();
    })
    .onUpdate(event => {
      const nextScale = clampScale(savedScale.value * event.scale);
      pinchChangingScale.value = Math.abs(event.scale - 1) > 0.002;
      const originX = boxWidth.value / 2;
      const originY = boxHeight.value / 2;
      const zoomX = panForZoom(savedFocalX.value, originX, savedX.value, savedScale.value, nextScale);
      const zoomY = panForZoom(savedFocalY.value, originY, savedY.value, savedScale.value, nextScale);
      panX.value = clampPan(zoomX + event.focalX - savedFocalX.value, boxWidth.value, nextScale);
      panY.value = clampPan(zoomY + event.focalY - savedFocalY.value, boxHeight.value, nextScale);
      scale.value = nextScale;
    })
    .onFinalize(() => {
      pinchChangingScale.value = false;
    });

  const twoFingerPan = Gesture.Pan()
    .minPointers(2)
    .onStart(() => {
      savedX.value = panX.value;
      savedY.value = panY.value;
      runOnJS(onDrawCancel)();
    })
    .onUpdate(event => {
      if (pinchChangingScale.value) return;
      panX.value = clampPan(savedX.value + event.translationX, boxWidth.value, scale.value);
      panY.value = clampPan(savedY.value + event.translationY, boxHeight.value, scale.value);
    });

  return Gesture.Simultaneous(draw, pinch, twoFingerPan);
}
