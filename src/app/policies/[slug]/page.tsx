import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { pageMetadata } from '@/lib/seo';
import { BUSINESS, LIVE } from '@/lib/business';
import { money } from '@/lib/data';
import { serverGet } from '@/lib/api/server';
import { shippingRulesSchema, type ShippingRules } from '@/lib/shipping';

type Section = { heading?: string; body: string[] };
type Policy = { title: string; description: string; sections: Section[] };

const B = BUSINESS;
const contact = `${B.email}${B.phone ? ` o al ${B.phone}` : ''} (${B.hours})`;

// Demo copy, shown until NEXT_PUBLIC_STORE_MODE=live.
const demo: Record<string, Policy> = {
  shipping: { title: 'Envíos y devoluciones', description: 'Plazos, costos de envío y devoluciones ilustrativos de FORMA., una tienda de demostración sin compras ni entregas reales.', sections: [{ body: ['Esta tienda es una demostración. No se procesan compras, envíos ni devoluciones reales.', 'Los plazos y costos de envío que aparecen en el checkout son ejemplos. No constituyen una oferta comercial.', 'Como política ilustrativa, una devolución podría solicitarse dentro de 30 días. No aplica a ningún producto real.'] }] },
  terms: { title: 'Términos de uso', description: 'Condiciones de uso de FORMA.: un proyecto de demostración con productos y precios ficticios, sin cobros ni entregas.', sections: [{ body: ['FORMA. es un proyecto de demostración. Los productos, precios, proveedores e inventarios son ficticios.', 'Crear un pedido no constituye una compra ni genera un cargo real. Los pagos funcionan únicamente en modo de prueba y no se entregan productos.', 'Fotografías ilustrativas obtenidas de Unsplash. No representan un catálogo comercial real ni garantizan las características de los objetos mostrados.'] }] },
};

function shippingCosts(rules: ShippingRules | null): string[] {
  if (!rules) return ['El costo de envío se calcula según el estado de destino y se muestra en el checkout antes de pagar.'];
  const lines: string[] = [];
  if (rules.zones.length) lines.push(`Tarifas por estado: ${rules.zones.map(z => `${z.state}, ${z.rate === 0 ? 'gratis' : money(z.rate)}`).join('; ')}.`);
  lines.push(rules.onlyZones
    ? 'Por ahora solo entregamos en los estados indicados. Si tu estado no aparece, escríbenos y buscaremos una alternativa.'
    : `${rules.zones.length ? 'Resto de la República Mexicana' : 'Envío a toda la República Mexicana'}: ${rules.flat === 0 ? 'sin costo' : money(rules.flat)} por pedido.`);
  if (rules.freeFrom != null) lines.push(`Envío gratis en compras desde ${money(rules.freeFrom)}.`);
  lines.push('El costo exacto se muestra en el checkout al elegir tu estado y siempre antes de pagar. Los precios están en pesos mexicanos (MXN) e incluyen IVA.');
  return lines;
}

function live(rules: ShippingRules | null): Record<string, Policy> {
  return {
    shipping: {
      title: 'Envíos, cambios y devoluciones',
      description: `Zonas de entrega, tarifas, plazos y condiciones de cambio y devolución de ${B.legalName}.`,
      sections: [
        { heading: 'Zonas y costo de envío', body: shippingCosts(rules) },
        { heading: 'Tiempos de entrega', body: [
          `Preparamos y despachamos tu pedido en ${B.handlingTime} a partir de la confirmación del pago. La entrega estimada es de ${rules?.deliveryEstimate ?? '3 a 7 días hábiles'} desde el despacho, según la paquetería y el destino.`,
          'Cuando tu pedido sale a reparto te enviamos un correo con la paquetería y el número de guía. También puedes consultar el estado en la sección «Cuenta» de este sitio.',
          'Verifica que la dirección y el teléfono sean correctos: un dato erróneo puede generar reintentos o devoluciones con costo adicional. Si no hay quien reciba, la paquetería hará un nuevo intento o dejará un aviso para recolección.',
        ] },
        { heading: 'Paquetes dañados o incompletos', body: [
          `Si tu paquete llega dañado, abierto o incompleto, escríbenos a ${B.email} dentro de las 48 horas siguientes a la entrega con tu número de pedido y fotografías. Repondremos la pieza o reembolsaremos su importe sin costo para ti.`,
        ] },
        { heading: 'Cambios y devoluciones', body: [
          `Tienes ${B.returnDays} días naturales a partir de la entrega para solicitar un cambio o devolución. El producto debe estar sin uso, en su empaque original y con todos sus accesorios.`,
          `Para iniciar el trámite escribe a ${contact} indicando tu número de pedido y el motivo. Te responderemos con las instrucciones de envío.`,
          'Si la devolución se debe a un defecto o a un error nuestro, cubrimos el costo del envío de regreso. En cualquier otro caso, el envío de regreso corre por tu cuenta.',
          'Además, conforme al artículo 56 de la Ley Federal de Protección al Consumidor, puedes revocar tu compra sin responsabilidad dentro de los cinco días hábiles siguientes a la entrega, siempre que el producto no haya sido usado.',
        ] },
        { heading: 'Reembolsos', body: [
          'Una vez recibido y revisado el producto, realizamos el reembolso a través de Mercado Pago al mismo medio de pago utilizado, en un máximo de 10 días hábiles. El tiempo en que se refleja depende de tu banco o emisor.',
        ] },
        { heading: 'Garantía', body: [
          'Todos los productos cuentan con garantía contra defectos de fabricación por al menos 90 días a partir de la entrega. La garantía no cubre el desgaste normal ni daños por uso indebido.',
        ] },
      ],
    },
    terms: {
      title: 'Términos y condiciones',
      description: `Condiciones de compra y uso del sitio de ${B.legalName}.`,
      sections: [
        { heading: 'Quiénes somos', body: [
          `Este sitio es operado por ${B.legalName}${B.taxId ? `, RFC ${B.taxId}` : ''}, con domicilio en ${B.address}. Puedes contactarnos en ${contact}.`,
          'Al navegar o comprar en este sitio aceptas estos términos. Si no estás de acuerdo, te pedimos no utilizarlo.',
        ] },
        { heading: 'Productos y precios', body: [
          'Los precios se muestran en pesos mexicanos (MXN) e incluyen IVA. El costo de envío se informa por separado antes de pagar.',
          'La disponibilidad se confirma al crear el pedido. Las fotografías son de referencia: el color y el acabado pueden variar ligeramente respecto a la pieza física.',
          'Podemos actualizar precios y catálogo en cualquier momento; los cambios no afectan a los pedidos ya pagados.',
        ] },
        { heading: 'Pedidos y pago', body: [
          'El pago se realiza en la plataforma de Mercado Pago, con los medios que esta ofrece. No almacenamos datos de tarjetas.',
          'Tu pedido queda confirmado cuando Mercado Pago aprueba el pago; recibirás un correo de confirmación. Un pedido sin pago puede cancelarse y sus piezas volver al inventario.',
          'Podemos cancelar y reembolsar íntegramente un pedido ante un error evidente de precio o de inventario, o por sospecha fundada de fraude.',
          `Si necesitas factura (CFDI), solicítala a ${B.email} dentro del mes de tu compra, indicando tus datos fiscales y número de pedido.`,
        ] },
        { heading: 'Envíos, cambios y devoluciones', body: [
          'Las zonas de entrega, tarifas, plazos y el procedimiento de cambio, devolución y garantía se describen en la página «Envíos, cambios y devoluciones», que forma parte de estos términos.',
        ] },
        { heading: 'Cuenta y uso del sitio', body: [
          'Eres responsable de la veracidad de tus datos y de mantener la confidencialidad de tu contraseña. No está permitido usar el sitio para fines ilícitos ni intentar vulnerar su seguridad.',
          'El tratamiento de tus datos personales se rige por nuestro Aviso de privacidad.',
        ] },
        { heading: 'Propiedad intelectual', body: [
          'La marca, el diseño, los textos y las imágenes de este sitio pertenecen a sus titulares y no pueden reproducirse con fines comerciales sin autorización.',
        ] },
        { heading: 'Legislación y controversias', body: [
          'Estos términos se rigen por las leyes de los Estados Unidos Mexicanos. Para cualquier controversia puedes acudir a la Procuraduría Federal del Consumidor (PROFECO); en su defecto, las partes se someten a los tribunales competentes del domicilio del consumidor.',
        ] },
      ],
    },
  };
}

async function policy(slug: string): Promise<Policy | undefined> {
  if (!LIVE) return demo[slug];
  if (slug !== 'shipping' && slug !== 'terms') return undefined;
  const rules = slug === 'shipping' ? await serverGet('/checkout/shipping', shippingRulesSchema).catch(() => null) : null;
  return live(rules)[slug];
}

export async function generateMetadata({params}:{params:Promise<{slug:string}>}):Promise<Metadata>{const {slug}=await params;const p=await policy(slug);return p?pageMetadata({title:p.title,description:p.description,path:`/policies/${slug}`}):{}}
export default async function Page({params}:{params:Promise<{slug:string}>}){const p=await policy((await params).slug);if(!p)notFound();return <article className="page policy-page"><p className="eyebrow">{LIVE?'Información para tu compra':'Información de la demostración'}</p><h1>{p.title}</h1>{p.sections.map((s,i)=><section key={i}>{s.heading&&<h2>{s.heading}</h2>}{s.body.map(t=><p key={t}>{t}</p>)}</section>)}</article>}
