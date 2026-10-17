export type PermissionKind =
  | 'overlay'
  | 'backgroundPopup'
  | 'autostart'
  | 'battery'
  | 'notifications';

export type PermissionNeed = 'requiredForBubble' | 'optional';

const KINDS: PermissionKind[] = [
  'overlay',
  'backgroundPopup',
  'autostart',
  'battery',
  'notifications',
];

export function permissionKinds(): PermissionKind[] {
  return KINDS;
}

export function permissionNeed(kind: PermissionKind): PermissionNeed {
  return kind === 'overlay' ? 'requiredForBubble' : 'optional';
}

export function blocksInAppCutout(_kind: PermissionKind): boolean {
  return false;
}

export function bubbleCanStart(overlayGranted: boolean, enabled: boolean): boolean {
  return enabled && overlayGranted;
}
