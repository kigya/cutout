import { NativeModules } from 'react-native';
import type { PermissionKind, PermissionNeed } from '../logic/permissions';

export type GrantStatus = 'granted' | 'denied' | 'unknown';

export type HomeRow = {
  kind: PermissionKind;
  title: string;
  status: GrantStatus;
  need: PermissionNeed;
  deniedCount?: number;
  permanentlyDenied?: boolean;
};

export type HomeSnapshot = {
  bubbleSupported?: boolean;
  rows: HomeRow[];
  bubbleEnabled: boolean;
  showXiaomiTip: boolean;
  clipboardReady: boolean;
};

export type EditSession = {
  status?: 'ready' | 'manual' | 'svg' | 'failed';
  message?: string;
  originalPath?: string;
  cutoutPath?: string;
  width?: number;
  height?: number;
};

export type BubbleSnapshot = {
  revision?: number;
  taskId?: string;
  stage?: string;
  expanded: boolean;
  anchorEnd: boolean;
  phase: 'idle' | 'working' | 'copied' | 'failed';
  clipboardReady: boolean;
  readingClipboard: boolean;
  message: string | null;
  previewPath: string | null;
  rasterizedSvg: boolean;
  editReady: boolean;
  percent: number;
};

export type LocaleState = {
  selected: string;
  system: string;
};

export type MaskResult = {
  status: 'ready' | 'empty' | 'failed';
  path?: string;
  message?: string;
};

export type AppPreferences = { theme: 'system' | 'light' | 'dark'; analytics: boolean; crashes: boolean; diagnosticsNoticePending?: boolean };

type EraseNative = {
  getPreferences?(): Promise<AppPreferences>;
  setPreferences?(theme: string, analytics: boolean, crashes: boolean): Promise<void>;
  logProductEvent?(name: string, values: Record<string, string | number | boolean>): Promise<void>;
  acknowledgeDiagnosticsNotice?(): Promise<void>;
  beginPermissionRequest?(kind: string): Promise<string>;
  completePermissionRequest?(kind: string, attemptId: string, blocked: boolean): Promise<void>;
  getHome(): Promise<HomeSnapshot>;
  prepareModel(): Promise<string>;
  setBubble(enabled: boolean): Promise<void>;
  hideBubble(): Promise<void>;
  openPermission(kind: string): Promise<void>;
  pickAndCut(mode: string): Promise<EditSession>;
  cutClipboard(): Promise<EditSession>;
  cutFixture(): Promise<EditSession>;
  cancelCut(): Promise<void>;
  bubbleAction(action: string): Promise<BubbleSnapshot>;
  getBubbleState(): Promise<BubbleSnapshot>;
  readAlpha(path: string): Promise<string>;
  writeMasked(originalPath: string, alphaBase64: string, width: number, height: number): Promise<MaskResult>;
  publishPng(path: string): Promise<string>;
  saveToGallery(path: string): Promise<string>;
  getLocale(): Promise<LocaleState>;
  setLocale(tag: string): Promise<void>;
  restart(): Promise<void>;
  consumePendingEdit(): Promise<EditSession | null>;
  moveBubble(dx: number, dy: number): void;
  settleBubble(): Promise<boolean>;
  setBubbleSize(width: number, height: number): void;
};

export const Erase = NativeModules.Erase as EraseNative;

export function canEdit(session: EditSession | null): session is EditSession & {
  originalPath: string;
  cutoutPath: string;
  width: number;
  height: number;
} {
  if (!session?.originalPath || !session.cutoutPath || !session.width || !session.height) return false;
  return session.status == null || session.status === 'ready' || session.status === 'manual';
}
