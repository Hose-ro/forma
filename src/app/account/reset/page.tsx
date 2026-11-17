import { AccountToken } from '@/components/account-token';
import { NO_INDEX } from '@/lib/seo';
export const metadata={title:'Restablecer contraseña',robots:NO_INDEX,referrer:'no-referrer' as const};
export default function Page(){return <AccountToken mode="reset"/>}
