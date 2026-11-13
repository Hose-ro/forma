import type { Metadata } from 'next';
import { CookiePolicy } from '@/components/legal';
import { pageMetadata } from '@/lib/seo';
export const metadata:Metadata=pageMetadata({title:'Aviso de cookies',description:'Qué cookies y datos de almacenamiento local usa FORMA., para qué sirven y cómo gestionarlos.',path:'/policies/cookies'});
export default function Page(){return <CookiePolicy/>}
