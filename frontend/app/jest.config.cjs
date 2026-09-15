/** Unit tests only. Anything that needs a browser belongs in e2e/ under Playwright. */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'jsdom',
  roots: ['<rootDir>/src'],
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
