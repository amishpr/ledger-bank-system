import { Router } from "express";
import { getSpendingBreakdown } from "../ledger/insightsService.js";
import { asyncHandler } from "./asyncHandler.js";

export const insightsRouter = Router();

insightsRouter.get(
  "/spending",
  asyncHandler(async (_req, res) => {
    res.json(await getSpendingBreakdown());
  }),
);
