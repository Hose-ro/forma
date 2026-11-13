import Link from 'next/link';
// Next.js already emits noindex for 404 responses.
export const metadata={title:'Página no encontrada'};
export default function NotFound(){return <div className="page empty"><p>404 / Fuera de la colección</p><h1>Volvamos a lo <i>esencial.</i></h1><Link className="button" href="/shop">Explorar colección</Link></div>}
