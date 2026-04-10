import { execSync } from "node:child_process";

export async function setup() {
  execSync("npx prisma db push --skip-generate --accept-data-loss", {
    cwd: import.meta.dirname,
    env: { ...process.env, DATABASE_URL: "file:./test.db" },
    stdio: "inherit",
  });
}
