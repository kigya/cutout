import { BRAND_MARK_PATHS, BRAND_MARK_SCALE, BRAND_MARK_VIEWBOX } from './markPaths';
import { SPLASH_MARK_SIZE } from '../SplashScreen';

declare const __dirname: string;
declare const require: (name: string) => { readFileSync(path: string, encoding: 'utf8'): string };
const { readFileSync } = require('fs');
const resolveFromComponents = (path: string) => `${__dirname}/${path}`;
const svg = readFileSync(resolveFromComponents('../../../assets/brand/cutout-mark.svg'), 'utf8');
const svgPathIds: Record<keyof typeof BRAND_MARK_PATHS, string> = {
  avatar: 'avatar',
  edge: 'dashed-edge',
  sparkleLarge: 'sparkle-large',
  sparkleSmall: 'sparkle-small',
  eraserA: 'eraser-back',
  eraserB: 'eraser-front',
};

test('editable SVG and Skia contours share the clean alpha-traced Bezier geometry', () => {
  expect(svg).not.toMatch(/<image\b|data:image\//i);
  for (const [key, id] of Object.entries(svgPathIds) as [keyof typeof BRAND_MARK_PATHS, string][]) {
    const source = svg.match(new RegExp(`<path id="${id}"[^>]* d="([^"]+)"`));
    expect(source?.[1]).toBe(BRAND_MARK_PATHS[key]);
  }
  expect(svg).toContain('fill-rule="evenodd"');
  expect(BRAND_MARK_PATHS.avatar).toMatch(/C/);
  expect(BRAND_MARK_PATHS.edge.match(/M /g)).toHaveLength(13);
  expect(BRAND_MARK_PATHS.eraserA).not.toBe(BRAND_MARK_PATHS.eraserB);
});

test('traced mark stays centered within the splash canvas at the former visible ink size', () => {
  const coordinates = Object.values(BRAND_MARK_PATHS)
    .flatMap(path => path.match(/-?\d+(?:\.\d+)?/g)?.map(Number) ?? []);
  const xs = coordinates.filter((_value, index) => index % 2 === 0);
  const ys = coordinates.filter((_value, index) => index % 2 === 1);
  const footprint = Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys));

  expect(BRAND_MARK_VIEWBOX * BRAND_MARK_SCALE).toBe(SPLASH_MARK_SIZE);
  expect(footprint * BRAND_MARK_SCALE).toBeCloseTo(137.6, 0);
  expect(Math.min(...coordinates)).toBeGreaterThan(0);
  expect(Math.max(...coordinates)).toBeLessThan(BRAND_MARK_VIEWBOX);
});
