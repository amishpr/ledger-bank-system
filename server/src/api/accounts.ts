import { Router } from "express";
import { buildStatementCsv } from "../ledger/csv.js";
import { createAccount, getAccount, getStatement, listAccounts } from "../ledger/ledgerService.js";
import { asyncHandler } from "./asyncHandler.js";
import { createAccountSchema } from "./validation.js";

export const accountsRouter = Router();

accountsRouter.get(
  "/",
  asyncHandler(async (_req, res) => {
    const accounts = await listAccounts();
    res.json(accounts);
  }),
);

accountsRouter.post(
  "/",
  asyncHandler(async (req, res) => {
    const input = createAccountSchema.parse(req.body);
    const account = await createAccount(input);
    res.status(201).json(account);
  }),
);

accountsRouter.get(
  "/:id",
  asyncHandler(async (req, res) => {
    const account = await getAccount(req.params.id!);
    res.json(account);
  }),
);

accountsRouter.get(
  "/:id/statement",
  asyncHandler(async (req, res) => {
    const limit = req.query.limit ? Number(req.query.limit) : 50;
    const lines = await getStatement(req.params.id!, limit);
    res.json(lines);
  }),
);

accountsRouter.get(
  "/:id/statement/export",
  asyncHandler(async (req, res) => {
    const account = await getAccount(req.params.id!);
    const lines = await getStatement(req.params.id!, Number.MAX_SAFE_INTEGER);
    const csv = buildStatementCsv(lines);
    const slug = account.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || "account";
    res.setHeader("Content-Type", "text/csv; charset=utf-8");
    res.setHeader("Content-Disposition", `attachment; filename="${slug}-statement.csv"`);
    res.send(csv);
  }),
);
