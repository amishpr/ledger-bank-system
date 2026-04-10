import { Router } from "express";
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
