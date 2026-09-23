/**
 * The path this app is mounted at, as the browser sees it.
 *
 * <p>"/" when the app has a host to itself, "/Arak/" when it sits behind a
 * proxy that routes several applications by path prefix. Everything that builds
 * a URL — the router's basename, the API client's base, any link written by
 * hand — has to agree on it, so it is read once, here.
 *
 * Read from a meta tag rather than `import.meta.env.BASE_URL`, which is the
 * obvious answer and the wrong one: `import.meta` is ES-module syntax, the unit
 * tests run through ts-jest in CommonJS, and importing it anywhere in the API
 * layer breaks every test that touches the API layer. The meta tag is written
 * by Vite from the same value at build time and is simply absent under jsdom,
 * where "/" is the right answer anyway.
 */
function read(): string {
  if (typeof document === 'undefined') {
    return '/';
  }
  const meta = document.querySelector('meta[name="arak-base"]');
  const raw = meta?.getAttribute('content') ?? '/';
  // Unsubstituted in a context Vite never processed — treat it as unset rather
  // than as a directory literally called "%BASE_URL%".
  if (!raw || raw.startsWith('%')) {
    return '/';
  }
  const leading = raw.startsWith('/') ? raw : `/${raw}`;
  return leading.endsWith('/') ? leading : `${leading}/`;
}

/** Always begins and ends with a slash, so "/" is the no-prefix case. */
export const BASE_PATH = read();

/**
 * Prefixes an app-absolute path with the mount point.
 *
 * `withBase('api')` → "/api" on its own host, "/Arak/api" behind a prefix.
 */
export function withBase(path: string): string {
  return BASE_PATH + path.replace(/^\/+/, '');
}
