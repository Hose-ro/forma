import { Suspense } from 'react';
import { Account } from '@/components/account';
export const metadata={title:'Mi cuenta'};
export default function Page(){return <Suspense fallback={<div className="page skeleton"/>}><Account/></Suspense>}
