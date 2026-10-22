export function previewFrame(
  windowWidth: number,
  windowHeight: number,
  imageWidth: number,
  imageHeight: number,
): { width: number; height: number } {
  const maxW = Math.min(windowWidth - 40, 640);
  const maxH = Math.min(windowHeight * 0.5, 720);
  const aspect = imageHeight / Math.max(imageWidth, 1);
  let frameW = maxW;
  let frameH = frameW * aspect;
  if (frameH > maxH) {
    frameH = maxH;
    frameW = frameH / aspect;
  }
  return {
    width: Math.max(1, Math.round(frameW)),
    height: Math.max(1, Math.round(frameH)),
  };
}

export function clearHeldStroke(previewChanged: boolean, imageReady: boolean): boolean {
  return previewChanged && imageReady;
}
