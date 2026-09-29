import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";

// Deployed by .github/workflows/deploy-pages.yml to GitHub Pages under the custom domain
// geoshutter.sarmad.no (set in the repository's Pages settings).
export default defineConfig({
  site: "https://geoshutter.sarmad.no",
  base: "/",
  integrations: [sitemap()]
});
