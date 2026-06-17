// The demo's stand-in for the network hop. The Express app registers a
// `json replacer` that turns every BigInt into a string, and Date values
// become ISO strings through JSON.stringify's own toJSON handling. Running
// the same round-trip here means the dashboard receives objects that are
// shape-for-shape identical to the ones the real API sends, instead of
// receiving live BigInts and Dates that would quietly behave differently.
export function toWire<T>(value: unknown): T {
  return JSON.parse(JSON.stringify(value, (_key, v: unknown) => (typeof v === "bigint" ? v.toString() : v))) as T;
}
