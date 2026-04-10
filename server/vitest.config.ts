import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    env: { DATABASE_URL: "file:./test.db" },
    globalSetup: "./vitest.global-setup.ts",
    // Tests share one SQLite file and clean tables in beforeEach, so they
    // must not run as separate concurrent processes against it.
    fileParallelism: false,
  },
});
