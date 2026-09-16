/** Unit tests only. Anything that needs a browser belongs in e2e/ under Playwright. */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'jsdom',
  roots: ['<rootDir>/src'],
  // The design system is vendored OUTSIDE this package (frontend/ui-core-components),
  // so walking up from its files never reaches a node_modules. Point Jest at ours
  // explicitly; Vite and tsc are told the same thing in their own configs.
  modulePaths: ['<rootDir>/node_modules'],
  setupFilesAfterEnv: ['<rootDir>/src/setupTests.ts'],
  moduleNameMapper: {
    '\\.(css|less)$': '<rootDir>/src/__mocks__/styleMock.cjs',
    '^~/(.*)$': '<rootDir>/src/$1',
    '^@openmetadata/ui-core-components$': '<rootDir>/../ui-core-components/src',
    '^@openmetadata/ui-core-components/(.*)$': '<rootDir>/../ui-core-components/src/$1',
    '^@/(.*)$': '<rootDir>/../ui-core-components/src/$1',
  },
  transform: {
    '^.+\\.tsx?$': ['ts-jest', { tsconfig: { jsx: 'react-jsx' } }],
  },
};
