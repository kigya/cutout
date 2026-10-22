export type PermissionKind =
  | 'overlay'
  | 'backgroundPopup'
  | 'autostart'
  | 'battery'
  | 'notifications';

export type PermissionNeed = 'requiredForBubble' | 'optional';

export type PermissionHistory = {
  deniedCount?: number;
  permanentlyDenied?: boolean;
};

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

export function permissionNeed(kind: PermissionKind, bubbleEnabled = false): PermissionNeed {
  return kind === 'overlay' && bubbleEnabled ? 'requiredForBubble' : 'optional';
}

export function blocksInAppCutout(_kind: PermissionKind): boolean {
  return false;
}

export function bubbleCanStart(overlayGranted: boolean, enabled: boolean): boolean {
  return enabled && overlayGranted;
}

/** Give a direct route to system settings after repeated denial or a blocked request. */
export function permissionNeedsHelp(history: PermissionHistory): boolean {
  return history.permanentlyDenied === true || (history.deniedCount ?? 0) >= 2;
}
