import { configure } from '@testing-library/react';
import '@testing-library/jest-dom';

/*
 * Testing Library gives `findBy*` its own 1s budget, separate from Jest's
 * per-test one. On this machine the first query of a suite waits behind
 * ts-jest compiling the vendored design system, so a second is spent before
 * the component has rendered anything -- and the suites that failed were
 * simply whichever ones lost the race that run, a different set each time.
 * Waiting longer for an element that is coming costs nothing; a query for an
 * element that never arrives still fails, just later.
 */
configure({ asyncUtilTimeout: 15000 });

/*
 * jsdom ships neither `TextEncoder`/`TextDecoder` nor a `Blob` that can be
 * read back, though every browser has had all three for years. Code that
 * writes a file -- the CSV and Excel export -- is otherwise untestable here,
 * and moving it to Playwright would test the download dialog rather than the
 * bytes. Node's own implementations are the same ones the browser exposes.
 */
import { TextDecoder, TextEncoder } from 'node:util';

Object.assign(globalThis, {
  TextEncoder: globalThis.TextEncoder ?? TextEncoder,
  TextDecoder: globalThis.TextDecoder ?? TextDecoder,
});

if (typeof Blob !== 'undefined' && !Blob.prototype.arrayBuffer) {
  // Through FileReader rather than `new Response(blob)`: jsdom has the former
  // and not the latter.
  Blob.prototype.arrayBuffer = function arrayBuffer(this: Blob) {
    return new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as ArrayBuffer);
      reader.onerror = () => reject(reader.error);
      reader.readAsArrayBuffer(this);
    });
  };
}
