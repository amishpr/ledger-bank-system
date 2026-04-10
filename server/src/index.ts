import "dotenv/config";
import cors from "cors";
import express, { type NextFunction, type Request, type Response } from "express";
import { createServer } from "node:http";
import { accountsRouter } from "./api/accounts.js";
import { transactionsRouter } from "./api/transactions.js";
import { LedgerError } from "./ledger/errors.js";
import { initWebSocketServer } from "./realtime/ws.js";
import { ZodError } from "zod";

const app = express();

// BigInt cent amounts round-trip through the API as strings, never
// silently coerced to (lossy) JS numbers.
app.set("json replacer", (_key: string, value: unknown) => (typeof value === "bigint" ? value.toString() : value));

app.use(cors({ origin: process.env.CORS_ORIGIN ?? "http://localhost:5173" }));
app.use(express.json());

app.get("/health", (_req, res) => res.json({ ok: true }));
app.use("/accounts", accountsRouter);
app.use("/transactions", transactionsRouter);

app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  if (err instanceof LedgerError) {
    res.status(err.httpStatus).json({ error: err.code, message: err.message });
    return;
  }
  if (err instanceof ZodError) {
    res.status(400).json({ error: "VALIDATION_ERROR", message: err.issues.map((i) => i.message).join("; ") });
    return;
  }
  console.error(err);
  res.status(500).json({ error: "INTERNAL_ERROR", message: "Something went wrong" });
});

const port = Number(process.env.PORT ?? 4000);
const httpServer = createServer(app);
initWebSocketServer(httpServer);

httpServer.listen(port, () => {
  console.log(`ledger-server listening on http://localhost:${port} (ws on /ws)`);
});
