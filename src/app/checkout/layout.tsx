import type { Metadata } from 'next';
import { NO_INDEX } from '@/lib/seo';
// Checkout, payment returns and confirmations are per-customer: keep the whole segment out of search results.
export const metadata:Metadata={robots:NO_INDEX};
export default function CheckoutLayout({children}:{children:React.ReactNode}){return children}
