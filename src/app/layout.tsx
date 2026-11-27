import type { Metadata } from 'next';
import { Suspense } from 'react';
import { MarketingRuntime } from '@/components/marketing-runtime';
import { headers } from 'next/headers';
import { Instrument_Serif, Manrope } from 'next/font/google';
import { Shell } from '@/components/shell';
import { DEFAULT_OG_IMAGE, SITE_DESCRIPTION, SITE_NAME, SITE_TAGLINE, siteUrl } from '@/lib/seo';
import { THEME_COLORS, THEME_META_ID, themeInitializationScript } from '@/lib/theme';
import '@/styles/globals.css';
// Per-request CSP nonces require server rendering instead of shared static HTML.
export const dynamic = 'force-dynamic';
const display=Instrument_Serif({weight:'400',subsets:['latin'],style:['normal','italic'],variable:'--font-instrument'});
const body=Manrope({subsets:['latin'],variable:'--font-manrope'});
// Defaults for every route. No canonical here: a root canonical would be inherited by every page.
export const metadata:Metadata={metadataBase:siteUrl(),applicationName:SITE_NAME,icons:{icon:{url:'/brand/favicon.svg',type:'image/svg+xml',sizes:'any'}},title:{default:SITE_TAGLINE,template:`%s | ${SITE_NAME}`},description:SITE_DESCRIPTION,openGraph:{type:'website',siteName:SITE_NAME,locale:'es_MX',title:SITE_TAGLINE,description:SITE_DESCRIPTION,images:[DEFAULT_OG_IMAGE]},twitter:{card:'summary_large_image',title:SITE_TAGLINE,description:SITE_DESCRIPTION,images:[DEFAULT_OG_IMAGE.url]}};
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const nonce = (await headers()).get('x-nonce') ?? undefined;

  return (
    // The font variables must live on <html>: tokens.css resolves them from :root.
    <html lang="es" className={`${display.variable} ${body.variable}`} suppressHydrationWarning>
      <head>
        <meta id={THEME_META_ID} name="theme-color" content={THEME_COLORS.light} suppressHydrationWarning />
        <script nonce={nonce} dangerouslySetInnerHTML={{ __html: themeInitializationScript }} />
      </head>
      <body>
        <Shell>{children}</Shell>
        <Suspense>
          <MarketingRuntime frameOrigin={new URL(process.env.BACKEND_URL || 'http://localhost:8080').origin} />
        </Suspense>
      </body>
    </html>
  );
}
