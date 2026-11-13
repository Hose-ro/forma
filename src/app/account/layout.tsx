import type { Metadata } from 'next';
import { NO_INDEX } from '@/lib/seo';
// Account and order pages are private: keep the whole segment out of search results.
export const metadata:Metadata={robots:NO_INDEX};
export default function AccountLayout({children}:{children:React.ReactNode}){return children}
