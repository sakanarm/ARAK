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
