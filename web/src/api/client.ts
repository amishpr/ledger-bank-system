import { demoApi } from "../demo/api";
import { DEMO_MODE } from "../demo/mode";
import type { LedgerApi } from "./contract";
import { httpApi } from "./http";

export { ApiRequestError } from "./errors";

// The one place the app decides which backend it is talking to. Both
// implementations satisfy the same LedgerApi interface, so every component
// below this line is written against the interface and never learns which
// one it got.
export const api: LedgerApi = DEMO_MODE ? demoApi : httpApi;
