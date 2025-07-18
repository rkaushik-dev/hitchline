import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
    clearMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      reporter: ['text-summary'],
      // The floor is a little under what the suite reads today, so a real
      // regression fails the build and an ordinary refactor does not.
      thresholds: {
        statements: 95,
        branches: 88,
        functions: 95,
        lines: 95,
      },
    },
  },
});
