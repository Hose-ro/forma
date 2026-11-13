import type { Metadata } from 'next';
import { Instrument_Serif, Manrope } from 'next/font/google';
import { Shell } from '@/components/shell';
import '@/styles/globals.css';
// Per-request CSP nonces require server rendering instead of shared static HTML.
export const dynamic = 'force-dynamic';
const display=Instrument_Serif({weight:'400',subsets:['latin'],style:['normal','italic'],variable:'--font-instrument'});
const body=Manrope({subsets:['latin'],variable:'--font-manrope'});
export const metadata:Metadata={title:{default:'FORMA. — Objects with character.',template:'%s | FORMA.'},description:'Una colección de audio, living y accesorios para lo cotidiano. Tienda de demostración.'};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="es"><body className={`${display.variable} ${body.variable}`}><Shell>{children}</Shell></body></html>}
