import type { Metadata } from 'next';
import { PrivacyNotice } from '@/components/legal';
import { pageMetadata } from '@/lib/seo';
export const metadata:Metadata=pageMetadata({title:'Aviso de privacidad',description:'Cómo FORMA. trata tus datos personales, qué cookies utiliza y cómo ejercer tus derechos ARCO.',path:'/policies/privacy'});
export default function Page(){return <PrivacyNotice/>}
