import type { MessageKey } from '../i18n/messages/en';
import type { PermissionKind } from './permissions';

export function permissionCopy(kind: PermissionKind): { title: MessageKey; body: MessageKey } {
  switch (kind) {
    case 'overlay':
      return { title: 'overlayTitle', body: 'overlayBody' };
    case 'backgroundPopup':
      return { title: 'popupTitle', body: 'popupBody' };
    case 'autostart':
      return { title: 'autostartTitle', body: 'autostartBody' };
    case 'battery':
      return { title: 'batteryTitle', body: 'batteryBody' };
    case 'notifications':
      return { title: 'notificationsTitle', body: 'notificationsBody' };
    /* istanbul ignore next -- PermissionKind has no remaining members */
    default: {
      const unknown: never = kind;
      return unknown;
    }
  }
}
