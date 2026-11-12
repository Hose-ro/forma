'use client';
import Link from 'next/link';
export default function Error({reset}:{error:Error;reset:()=>void}){return <div className="page empty"><p>Sin conexión con la colección</p><h1>Un momento de <i>pausa.</i></h1><p>No pudimos cargar esta página. Inténtalo de nuevo en unos segundos.</p><button className="button" onClick={reset}>Reintentar</button><Link className="text-link" href="/">Volver al inicio</Link></div>}
