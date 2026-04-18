import { Router } from "express";
import {
  createRecurringTransfer,
  deleteRecurringTransfer,
  listRecurringTransfers,
  toggleRecurringTransfer,
} from "../ledger/recurringService.js";
import { asyncHandler } from "./asyncHandler.js";
import { createRecurringTransferSchema } from "./validation.js";

export const recurringTransfersRouter = Router();

recurringTransfersRouter.get(
  "/",
  asyncHandler(async (_req, res) => {
    res.json(await listRecurringTransfers());
  }),
);

recurringTransfersRouter.post(
  "/",
  asyncHandler(async (req, res) => {
    const input = createRecurringTransferSchema.parse(req.body);
    const created = await createRecurringTransfer({
      ...input,
      startAt: input.startAt ? new Date(input.startAt) : undefined,
    });
    res.status(201).json(created);
  }),
);

recurringTransfersRouter.post(
  "/:id/toggle-active",
  asyncHandler(async (req, res) => {
    res.json(await toggleRecurringTransfer(req.params.id!));
  }),
);

recurringTransfersRouter.delete(
  "/:id",
  asyncHandler(async (req, res) => {
    await deleteRecurringTransfer(req.params.id!);
    res.status(204).end();
  }),
);
