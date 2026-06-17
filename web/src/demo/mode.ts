// Set by netlify.toml for the hosted build, and by `npm run dev:demo`
// locally. Anything else (including the normal `npm run dev`) talks to the
// real Express API.
export const DEMO_MODE = import.meta.env.VITE_DEMO === "true";
