import { useEffect, useState } from 'react';

export interface Route { path: string; parts: string[] }

function parse(): Route {
  const h = window.location.hash.replace(/^#\/?/, '');
  const parts = h.split('/').filter(Boolean).map(decodeURIComponent);
  return { path: '/' + parts.join('/'), parts };
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(parse);
  useEffect(() => {
    const on = () => setRoute(parse());
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}

export function navigate(path: string): void {
  window.location.hash = '#' + (path.startsWith('/') ? path : '/' + path);
}

export const href = (path: string) => '#' + (path.startsWith('/') ? path : '/' + path);
