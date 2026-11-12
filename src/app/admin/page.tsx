import { Suspense } from 'react';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { Admin } from '@/components/admin';
import { getSessionUser } from '@/lib/api/server';
export const metadata={title:'Administración'};
// Server-side gate: the backend session decides. Every admin API call is authorized again by Spring Security.
export default async function Page(){const user=await getSessionUser();if(!user)redirect('/account?next=/admin');if(user.role!=='ADMIN')return <div className="page empty"><p>403 / Acceso restringido</p><h1>Solo para el <i>estudio.</i></h1><p>Tu cuenta no tiene permisos de administración.</p><Link className="button" href="/">Volver a la tienda</Link></div>;return <Suspense fallback={<div className="page skeleton"/>}><Admin adminEmail={user.email}/></Suspense>}
