import { AppState, DeviceEventEmitter, Platform, type AppStateStatus } from 'react-native';
import { Erase, type MainActivitySnapshot } from './Erase';

let latest: MainActivitySnapshot | null = null;
const usesNativeState = () => Platform.OS === 'android' && typeof Erase?.getMainActivityState === 'function';

/** RN AppState also becomes active for the service surface; screen state must not. */
export const MainAppState = {
  get currentState(): AppStateStatus | null {
    if (!usesNativeState()) return (AppState.currentState as AppStateStatus | null | undefined) ?? null;
    return latest ? latest.resumed ? 'active' : 'background' : null;
  },
  addEventListener(_event: 'change', listener: (state: AppStateStatus) => void) {
    if (!usesNativeState()) return AppState.addEventListener('change', listener);
    let live = true;
    let revision = -1;
    let previous: boolean | null = null;
    const apply = (next: MainActivitySnapshot) => {
      if (!live || next.revision <= revision) return;
      revision = next.revision;
      if (!latest || next.revision >= latest.revision) latest = next;
      if (previous !== next.resumed) {
        previous = next.resumed;
        listener(next.resumed ? 'active' : 'background');
      }
    };
    const subscription = DeviceEventEmitter.addListener('mainActivityState', apply);
    void Erase.getMainActivityState!().then(apply).catch(() => undefined);
    return { remove() { live = false; subscription.remove(); } };
  },
};
