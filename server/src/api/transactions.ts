import { Router } from "express";
import { postTransaction, reverseTransaction } from "../ledger/ledgerService.js";
import { broadcast } from "../realtime/ws.js";
import { asyncHandler } from "./asyncHandler.js";
import { postTransactionSchema, reverseTransactionSchema } from "./validation.js";

export const transactionsRouter = Router();

transactionsRouter.post(
  "/",
  asyncHandler(async (req, res) => {
    const input = postTransactionSchema.parse(req.body);
    const result = await postTransaction(input);
    if (!result.replayed) {
      broadcast({ type: "transaction.posted", transaction: result.transaction, affectedAccountIds: result.affectedAccountIds });
    }
    res.status(result.replayed ? 200 : 201).json(result);
  }),
);

transactionsRouter.post(
  "/:id/reverse",
  asyncHandler(async (req, res) => {
    const input = reverseTransactionSchema.parse(req.body ?? {});
    const result = await reverseTransaction(req.params.id!, input.note);
    broadcast({ type: "transaction.reversed", transaction: result.transaction, affectedAccountIds: result.affectedAccountIds });
    res.status(201).json(result);
  }),
);
