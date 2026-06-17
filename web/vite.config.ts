import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// GitHub Pages serves a project site from https://user.github.io/<repo>/,
// so the built asset URLs have to carry that prefix. The Pages workflow
// passes it in VITE_BASE. Netlify and local dev serve from the root and
// leave it unset, which resolves to "/".
const base = `${(process.env.VITE_BASE ?? '').replace(/\/+$/, '')}/`

// https://vite.dev/config/
export default defineConfig({
  base,
  plugins: [react()],
})
