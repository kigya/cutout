// Model the asynchronous native iOS Modal dismissal event explicitly in tests.
const mockModalDismisses = [];
global.__modalDismisses = mockModalDismisses;
jest.mock('react-native/Libraries/Modal/Modal', () => {
  const React = require('react');
  class MockModal extends React.Component {
    componentDidUpdate(previous) {
      if (previous.visible !== false && this.props.visible === false && this.props.onDismiss) {
        mockModalDismisses.push(this.props.onDismiss);
      }
    }
    render() {
      return this.props.visible === false ? null : React.createElement('Modal', this.props, this.props.children);
    }
  }
  return { __esModule: true, default: MockModal };
});

jest.mock('react-native-css-interop', () => ({
  cssInterop: () => undefined,
  remapProps: () => undefined,
}));

const mockGestures = [];
const mockCompositions = [];
const mockShared = [];
const mockMaskImages = [];
const mockSkiaGroups = [];
global.__gestures = mockGestures;
global.__gestureCompositions = mockCompositions;
global.__shared = mockShared;
global.__maskImages = mockMaskImages;
global.__skiaGroups = mockSkiaGroups;

function mockGestureChain(kind) {
  const handlers = {};
  const api = {
    kind,
    handlers,
    onBegin(fn) {
      handlers.onBegin = fn;
      return api;
    },
    onStart(fn) {
      handlers.onStart = fn;
      return api;
    },
    onUpdate(fn) {
      handlers.onUpdate = fn;
      return api;
    },
    onEnd(fn) {
      handlers.onEnd = fn;
      return api;
    },
    onFinalize(fn) {
      handlers.onFinalize = fn;
      return api;
    },
    minPointers(value) {
      api.pointerCount = value;
      return api;
    },
    maxPointers(value) {
      api.pointerCount = value;
      return api;
    },
    minDistance() {
      return api;
    },
  };
  mockGestures.push(api);
  return api;
}

const mockSafeMetrics = {
  current: {
    insets: { top: 1, right: 2, bottom: 3, left: 4 },
    frame: { x: 0, y: 0, width: 800, height: 1200 },
  },
};
global.__safeMetrics = mockSafeMetrics;

let mockShader = { source: 'checker' };
global.__shader = {
  allow() {
    mockShader = { source: 'checker' };
  },
  block() {
    mockShader = null;
  },
};

let mockImageSize = { width: 120, height: 80 };
let mockImageAlpha = null;
let mockImagePixels;
let mockMakeImage = true;
let mockImageAvailable = true;
global.__imageSize = {
  set(width, height) {
    mockImageSize = { width, height };
  },
};
global.__imageAlpha = {
  set(alpha) {
    mockImageAlpha = alpha == null ? null : Array.from(alpha);
  },
};
global.__imagePixels = {
  set(pixels) {
    mockImagePixels = pixels == null ? pixels : Array.from(pixels);
  },
};
global.__makeImage = {
  set(allow) {
    mockMakeImage = allow;
  },
};
global.__imageAvailability = {
  set(available) {
    mockImageAvailable = available;
  },
};

jest.mock('react-native-reanimated', () => {
  const React = require('react');
  const NativeView = props => React.createElement('View', props, props.children);
  return {
    __esModule: true,
    default: {
      View: NativeView,
      createAnimatedComponent: component => component,
    },
    FadeIn: { duration: () => ({}) },
    Easing: { linear: value => value },
    useSharedValue: value => {
      const ref = require('react').useRef(null);
      if (!ref.current) {
        ref.current = { value };
        mockShared.push(ref.current);
      }
      return ref.current;
    },
    useAnimatedStyle: style => style(),
    useDerivedValue: compute => ({ value: compute() }),
    withTiming: value => value,
    withDelay: (_delay, value) => value,
    withRepeat: value => value,
    runOnJS: fn => (...args) => fn(...args),
  };
});

jest.mock('react-native-gesture-handler', () => {
  const React = require('react');
  const NativeView = props => React.createElement('View', props, props.children);
  return {
    GestureHandlerRootView: NativeView,
    GestureDetector: ({ children }) => children,
    Gesture: {
      Pan: () => mockGestureChain('pan'),
      Pinch: () => mockGestureChain('pinch'),
      Simultaneous: (...gestures) => {
        const composition = { kind: 'simultaneous', gestures };
        mockCompositions.push(composition);
        return composition;
      },
    },
  };
});

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaProvider: ({ children }) => children,
  useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
  get initialWindowMetrics() {
    return mockSafeMetrics.current;
  },
}));

function mockNode(name) {
  return function SkiaNode({ children }) {
    return require('react').createElement('View', { testID: name }, children);
  };
}

jest.mock('@shopify/react-native-skia', () => ({
  Canvas: mockNode('canvas'),
  Image: props => require('react').createElement('View', {
    testID: 'sk-image',
    fit: props.fit,
    blendMode: props.blendMode,
    x: props.x,
    y: props.y,
    width: props.width,
    height: props.height,
  }, props.children),
  LinearGradient: mockNode('gradient'),
  Rect: mockNode('rect'),
  Circle: mockNode('circle'),
  Fill: mockNode('fill'),
  Group: props => {
    mockSkiaGroups.push(props);
    return require('react').createElement('View', { testID: 'group' }, props.children);
  },
  Path: mockNode('path'),
  Shader: mockNode('shader'),
  StrokeCap: { Round: 'round' },
  StrokeJoin: { Round: 'round' },
  AlphaType: { Unknown: 0, Opaque: 1, Premul: 2, Unpremul: 3 },
  ColorType: { Unknown: 0, Alpha_8: 1, RGBA_8888: 4 },
  vec: (x, y) => ({ x, y }),
  useImage: (() => {
    const cache = new Map();
    return uri => {
      if (!uri || !mockImageAvailable) return null;
      const alphaKey = mockImageAlpha == null ? 'opaque' : mockImageAlpha.join(',');
      const key = `${uri}:${mockImageSize.width}x${mockImageSize.height}:${alphaKey}`;
      if (!cache.has(key)) {
        cache.set(key, {
          width: () => mockImageSize.width,
          height: () => mockImageSize.height,
          readPixels: (_x, _y, info) => {
            if (mockImagePixels === null) return null;
            const pixels = new Uint8Array(info.width * info.height * 4);
            pixels.fill(255);
            for (let index = 0; index < info.width * info.height; index += 1) {
              pixels[index * 4 + 3] = mockImageAlpha?.[index] ?? 255;
            }
            if (mockImagePixels !== undefined) return Uint8Array.from(mockImagePixels);
            return pixels;
          },
        });
      }
      return cache.get(key);
    };
  })(),
  Skia: {
    RuntimeEffect: { Make: () => mockShader },
    Data: { fromBytes: bytes => bytes },
    Image: {
      MakeImage: (info, data, bytesPerRow) => {
        if (!mockMakeImage) return null;
        const image = { info, pixels: Uint8Array.from(data), bytesPerRow };
        mockMaskImages.push(image);
        return image;
      },
    },
    Path: { Stroke: (path, options) => ({ path, options }) },
    PathBuilder: {
      Make: () => {
        const commands = [];
        return {
          reset() {
            commands.length = 0;
          },
          moveTo(x, y) {
            commands.push(['move', x, y]);
          },
          lineTo(x, y) {
            commands.push(['line', x, y]);
          },
          build() {
            return { commands: commands.slice() };
          },
        };
      },
    },
  },
}));

const mockErase = {
  getHome: jest.fn(async () => ({ rows: [], bubbleEnabled: false, showXiaomiTip: false, clipboardReady: false })),
  prepareModel: jest.fn(async () => 'ready'),
  setBubble: jest.fn(async () => undefined),
  hideBubble: jest.fn(async () => undefined),
  openPermission: jest.fn(async () => undefined),
  pickAndCut: jest.fn(async () => ({ status: 'ready', message: 'ready', originalPath: '/o', cutoutPath: '/c', width: 2, height: 2 })),
  cutClipboard: jest.fn(async () => ({ status: 'ready', message: 'ready', originalPath: '/o', cutoutPath: '/c', width: 2, height: 2 })),
  cutFixture: jest.fn(async () => ({ status: 'ready', message: 'ready', originalPath: '/o', cutoutPath: '/c', width: 2, height: 2 })),
  cancelCut: jest.fn(async () => undefined),
  bubbleAction: jest.fn(async () => ({
    expanded: true,
    anchorEnd: true,
    phase: 'idle',
    clipboardReady: false,
    readingClipboard: false,
    message: null,
    previewPath: null,
    rasterizedSvg: false,
    editReady: false,
    percent: 0,
  })),
  getBubbleState: jest.fn(async () => ({
    expanded: false,
    anchorEnd: true,
    phase: 'idle',
    clipboardReady: false,
    readingClipboard: false,
    message: null,
    previewPath: null,
    rasterizedSvg: false,
    editReady: false,
    percent: 0,
  })),
  readAlpha: jest.fn(async () => '////'),
  writeMasked: jest.fn(async () => ({ status: 'ready', path: '/masked.png' })),
  publishPng: jest.fn(async () => 'copied'),
  saveToGallery: jest.fn(async () => 'saved'),
  getLocale: jest.fn(async () => ({ selected: '', system: 'en' })),
  setLocale: jest.fn(async () => undefined),
  restart: jest.fn(async () => undefined),
  consumePendingEdit: jest.fn(async () => null),
  moveBubble: jest.fn(),
  settleBubble: jest.fn(async () => true),
  setBubbleSize: jest.fn(),
};

jest.mock('react-native', () => {
  const actual = jest.requireActual('react-native');
  actual.NativeModules.Erase = mockErase;
  return actual;
});

global.__erase = mockErase;

// Keep lifecycle cleanup safe after component tests restore their spies. The
// React Native Jest preset's native AppState implementation may return no
// subscription in this environment, whereas screens correctly call remove().
require('react-native').AppState.addEventListener = jest.fn(() => ({ remove: jest.fn() }));
