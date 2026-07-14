export type AppView = 'home' | 'square' | 'workspace' | 'editor' | 'imagegen' | 'myworks' | 'settings';

export type AppRoute =
  | { view: Exclude<AppView, 'editor'> }
  | { view: 'editor'; storyId: number };

const SIMPLE_VIEWS = new Set<Exclude<AppView, 'editor'>>([
  'home',
  'square',
  'workspace',
  'imagegen',
  'myworks',
  'settings',
]);

export function parseAppHash(hash: string): AppRoute | null {
  const path = hash.replace(/^#/, '').replace(/^\/+|\/+$/g, '');
  if (!path) return null;

  if (SIMPLE_VIEWS.has(path as Exclude<AppView, 'editor'>)) {
    return { view: path as Exclude<AppView, 'editor'> };
  }

  const editorMatch = /^editor\/(\d+)$/.exec(path);
  if (!editorMatch) return null;

  const storyId = Number(editorMatch[1]);
  return Number.isSafeInteger(storyId) && storyId > 0 ? { view: 'editor', storyId } : null;
}

export function appRouteHash(route: AppRoute): string {
  return route.view === 'editor' ? `#/editor/${route.storyId}` : `#/${route.view}`;
}

function updateHash(route: AppRoute, replace: boolean): void {
  const nextHash = appRouteHash(route);
  if (window.location.hash === nextHash) {
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }

  if (replace) {
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}${nextHash}`);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }

  window.location.hash = nextHash;
}

export function pushAppRoute(route: AppRoute): void {
  updateHash(route, false);
}

export function replaceAppRoute(route: AppRoute): void {
  updateHash(route, true);
}

export function isProtectedRoute(route: AppRoute): boolean {
  return route.view !== 'square';
}
