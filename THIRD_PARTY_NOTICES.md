# Third-party notices

Cutout includes or adapts the following software. Original copyright notices and applicable license terms remain in the distributed sources and dependency packages.

| Component | License | Source |
|---|---|---|
| React Native / Hermes | MIT | https://github.com/facebook/react-native |
| React Native Skia | MIT | https://github.com/Shopify/react-native-skia |
| React Native Free Canvas, adapted v2.1.0, commit 6605b6f | MIT, Copyright 2024 Lam | https://github.com/doublelam/react-native-free-canvas |
| React Native Gesture Handler | MIT | https://github.com/software-mansion/react-native-gesture-handler |
| React Native Reanimated / Worklets | MIT | https://github.com/software-mansion/react-native-reanimated |
| ONNX Runtime | MIT | https://github.com/microsoft/onnxruntime |
| ISNet general ONNX model | MIT | https://huggingface.co/imgly/isnet-general-onnx |
| AndroidSVG | Apache-2.0 | https://github.com/BigBadaboom/androidsvg |
| Firebase SDKs | Apache-2.0 | https://github.com/firebase |
| Google Play In-App Review 2.0.2 (Android) | Google Play Core SDK Terms of Service | https://developer.android.com/guide/playcore/license |

The bundled model revision, license metadata, and integrity hash are recorded in [the model notice](assets/licenses/isnet-model.md).

The adapted canvas source and complete MIT notice are in [src/editor/vendor](src/editor/vendor/PROVENANCE.md). Cutout extends this code for alpha-mask editing, undo/redo checkpoints, and coordinated gestures. No Expo example is bundled.

The app icon uses the supplied portrait/eraser artwork, recolored for Cutout. Platform icon exports share the same source artwork.
