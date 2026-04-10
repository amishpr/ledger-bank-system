import { z } from "zod";

// Accept a number or numeric string on the wire but always work in BigInt
// internally, so a JS float can never silently represent a cent amount.
export const amountMinorSchema = z.union([z.string(), z.number()]).transform((val, ctx) => {
  const str = typeof val === "number" ? String(val) : val;
  if (!/^\d+$/.test(str)) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: "amountMinor must be a non-negative integer" });
    return z.NEVER;
  }
  return BigInt(str);
});

export const entrySchema = z.object({
  accountId: z.string().min(1),
  direction: z.enum(["DEBIT", "CREDIT"]),
  amountMinor: amountMinorSchema,
});

export const postTransactionSchema = z.object({
  description: z.string().min(1).max(280),
  entries: z.array(entrySchema).min(2),
  idempotencyKey: z.string().min(1).max(255).optional(),
});

export const createAccountSchema = z.object({
  name: z.string().min(1).max(120),
  type: z.enum(["ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"]),
  currency: z.string().length(3).optional(),
});

export const reverseTransactionSchema = z.object({
  note: z.string().min(1).max(280).optional(),
});
