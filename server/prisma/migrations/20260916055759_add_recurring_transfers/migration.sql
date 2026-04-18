-- CreateTable
CREATE TABLE "RecurringTransfer" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "description" TEXT NOT NULL,
    "fromAccountId" TEXT NOT NULL,
    "toAccountId" TEXT NOT NULL,
    "amountMinor" BIGINT NOT NULL,
    "interval" TEXT NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "nextRunAt" DATETIME NOT NULL,
    "lastRunAt" DATETIME,
    "lastRunStatus" TEXT,
    "lastRunError" TEXT,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "RecurringTransfer_fromAccountId_fkey" FOREIGN KEY ("fromAccountId") REFERENCES "Account" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "RecurringTransfer_toAccountId_fkey" FOREIGN KEY ("toAccountId") REFERENCES "Account" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateIndex
CREATE INDEX "RecurringTransfer_active_nextRunAt_idx" ON "RecurringTransfer"("active", "nextRunAt");
