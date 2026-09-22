/** Unit tests only. Anything that needs a browser belongs in e2e/ under Playwright. */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'jsdom',
  /*
   * Jest's 5s default is a budget for the behaviour under test. Here the first
   * `findBy*` in a suite also pays for ts-jest type-checking and compiling the
   * whole vendored design-system module graph behind that page -- tens of
   * seconds on a loaded machine, and none of it the component's doing. At 5s
   * the suites rendering the heaviest pages (policy builder, app roles) passed
   * or failed depending on what else was running, and a different set failed
   * each run. A timeout that fires on compilation says nothing about the code,
   * so the budget is raised to one a genuinely hung test still trips.
   */
  testTimeout: 30000,
  roots: ['<rootDir>/src'],
  // The design system is vendored OUTSIDE this package (frontend/ui-core-components),
  // so walking up from its files never reaches a node_modules. Point Jest at ours
  // explicitly; Vite and tsc are told the same thing in their own configs.
  modulePaths: ['<rootDir>/node_modules'],
  setupFilesAfterEnv: ['<rootDir>/src/setupTests.ts'],
  moduleNameMapper: {
    '\\.(css|less)$': '<rootDir>/src/__mocks__/styleMock.cjs',
    '\\.(png|jpe?g|gif|svg|webp|avif|ico)$': '<rootDir>/src/__mocks__/fileMock.cjs',
    '^~/(.*)$': '<rootDir>/src/$1',
    '^@openmetadata/ui-core-components$': '<rootDir>/../ui-core-components/src',
    '^@openmetadata/ui-core-components/(.*)$': '<rootDir>/../ui-core-components/src/$1',
    '^@/(.*)$': '<rootDir>/../ui-core-components/src/$1',
  },
  transform: {
    '^.+\\.tsx?$': ['ts-jest', { tsconfig: { jsx: 'react-jsx' } }],
  },
};
