export type AppRoute = 'home' | 'feedback' | 'settings' | 'language' | 'processing' | 'result' | 'editor';

export function backTarget(route: AppRoute): AppRoute | null {
  switch (route) {
    case 'home':
      return null;
    case 'feedback':
    case 'language':
      return 'settings';
    case 'settings':
    case 'processing':
    case 'result':
    case 'editor':
      return 'home';
    /* istanbul ignore next -- AppRoute has no remaining members */
    default: {
      const unknown: never = route;
      return unknown;
    }
  }
}
