import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

// GitHub Pages serves a project site from https://user.github.io/<repo>/,
// so the built asset URLs have to carry that prefix. The Pages workflow
// passes it in VITE_BASE. Netlify and local dev serve from the root and
// leave it unset, which resolves to "/".
const base = `${(process.env.VITE_BASE ?? '').replace(/\/+$/, '')}/`

// The one URL that is meant to show up in Google. It is also what
// index.html points its canonical tag and its og:image at, so keep the two
// in step if the site ever moves to a custom domain.
const SITE_URL = 'https://amishpr-ledger.netlify.app'

// Netlify sets CONTEXT and URL on every build it runs. Requiring both to
// match means exactly one build is indexable: the production deploy of
// this site. Deploy previews (CONTEXT "deploy-preview"), branch deploys,
// any second Netlify site pointed at this repository, the GitHub Pages
// build and any local `npm run build` all fall through to noindex, so a
// duplicate of the dashboard can never quietly start competing with the
// real one in search results.
// https://docs.netlify.com/configure-builds/environment-variables/
const isCanonicalSite =
  process.env.CONTEXT === 'production' &&
  (process.env.URL ?? '').replace(/\/+$/, '') === SITE_URL

function robotsTxt(): string {
  if (!isCanonicalSite) {
    return [
      '# Not the canonical deployment of Ledger. The indexable copy is at',
      `# ${SITE_URL}/ and index.html on this build also carries a noindex`,
      '# tag, which is the part that actually does the work here: a',
      '# robots.txt is only read from a host root, so on GitHub Pages this',
      '# file sits at /<repo>/robots.txt where no crawler will look for it.',
      'User-agent: *',
      'Disallow: /',
      '',
    ].join('\n')
  }
  return [
    '# Everything here is a public demo with no real account data in it.',
    'User-agent: *',
    'Allow: /',
    '',
    `Sitemap: ${SITE_URL}/sitemap.xml`,
    '',
  ].join('\n')
}

function sitemapXml(): string {
  // One page, because the dashboard has no client-side router - every path
  // renders the same app. A sitemap this small earns its keep mainly by
  // giving Search Console something to confirm the canonical URL against.
  const lastmod = new Date().toISOString().slice(0, 10)
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    '  <url>',
    `    <loc>${SITE_URL}/</loc>`,
    `    <lastmod>${lastmod}</lastmod>`,
    '    <changefreq>monthly</changefreq>',
    '    <priority>1.0</priority>',
    '  </url>',
    '</urlset>',
    '',
  ].join('\n')
}

// Writes the two crawler files, and swaps the placeholder in index.html for
// a noindex tag on every build that is not the production Netlify one.
function seo(): Plugin {
  return {
    name: 'ledger-seo',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace(
        '<!--SEO-ROBOTS-->',
        isCanonicalSite ? '' : '<meta name="robots" content="noindex, nofollow" />',
      )
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'robots.txt', source: robotsTxt() })
      // A sitemap is only meaningful for the copy that is allowed to be
      // crawled; shipping one alongside "Disallow: /" is just noise.
      if (isCanonicalSite) {
        this.emitFile({ type: 'asset', fileName: 'sitemap.xml', source: sitemapXml() })
      }
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  base,
  plugins: [react(), seo()],
})
