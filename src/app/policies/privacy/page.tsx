import type { Metadata } from 'next';
import Link from 'next/link';
import { Database, Target, Share2, ShieldCheck, UserRound, MapPin, Receipt, Server, Eye, PenLine, Trash2, Hand, Lock, KeyRound, Gauge, Cookie } from 'lucide-react';
import { LegalDocument, LegalCards, LegalQuote, type LegalSection } from '@/components/legal';

export const metadata: Metadata = {
  title: 'Aviso de privacidad',
  description: 'Cómo FORMA. trata tus datos personales: qué recabamos, para qué, con quién los compartimos y cómo ejercer tus derechos ARCO.',
};

const CONTACT = 'hola@forma.example';

const sections: LegalSection[] = [
  {
    id: 'responsable', label: 'Responsable', title: <>Quién es <i>responsable.</i></>,
    content: <>
      <p>FORMA. («nosotros») es responsable del tratamiento de los datos personales que nos proporcionas a través de este sitio, conforme a la Ley Federal de Protección de Datos Personales en Posesión de los Particulares vigente en México y demás normativa aplicable.</p>
      <p>Para cualquier asunto relacionado con tu privacidad, escríbenos a <a href={`mailto:${CONTACT}`}>{CONTACT}</a>. Este contacto es ilustrativo y no opera mientras el sitio sea una demostración.</p>
    </>,
  },
  {
    id: 'datos', label: 'Datos que tratamos', title: <>Datos que <i>tratamos.</i></>,
    content: <>
      <p>Solo pedimos lo necesario para que puedas comprar y recibir tus objetos. No solicitamos datos personales sensibles.</p>
      <LegalCards items={[
        { icon: <UserRound size={18} />, title: 'Identificación y contacto', text: 'Nombre, apellidos, correo electrónico y teléfono.' },
        { icon: <MapPin size={18} />, title: 'Entrega', text: 'Calle y número, ciudad, estado, código postal y país.' },
        { icon: <KeyRound size={18} />, title: 'Cuenta', text: 'Correo y contraseña. La contraseña se guarda cifrada con un hash irreversible; nunca en texto plano.' },
        { icon: <Receipt size={18} />, title: 'Pedidos y pagos', text: 'Productos, importes, estado del pedido e identificadores del pago. Nunca recibimos ni guardamos los datos de tu tarjeta.' },
        { icon: <Server size={18} />, title: 'Datos técnicos', text: 'Dirección IP y datos de la solicitud, usados de forma temporal para aplicar límites de seguridad.' },
      ]} />
    </>,
  },
  {
    id: 'finalidades', label: 'Finalidades', title: <>Para qué los <i>usamos.</i></>,
    content: <>
      <p>Usamos tus datos para estas finalidades, necesarias para darte el servicio:</p>
      <ul>
        <li>Crear y administrar tu cuenta e iniciar tu sesión.</li>
        <li>Mantener tu bolsa de compra, aunque no tengas cuenta.</li>
        <li>Procesar, cobrar, enviar y dar seguimiento a tus pedidos.</li>
        <li>Mostrarte tu historial de pedidos y atender tus solicitudes.</li>
        <li>Proteger el sitio frente a abusos, fraude y accesos no autorizados.</li>
      </ul>
      <LegalQuote>No usamos tus datos para publicidad, perfiles comerciales ni envíos promocionales.</LegalQuote>
    </>,
  },
  {
    id: 'transferencias', label: 'Con quién compartimos', title: <>Con quién los <i>compartimos.</i></>,
    content: <>
      <p>No vendemos ni alquilamos tus datos. Solo los compartimos cuando es indispensable:</p>
      <LegalCards items={[
        { icon: <Receipt size={18} />, title: 'Mercado Pago', text: 'Procesa el pago en su propia plataforma. Le enviamos el pedido y el importe; los datos de tu tarjeta los capturas directamente con ellos. En esta demostración solo funciona en modo de prueba.' },
        { icon: <Share2 size={18} />, title: 'Autoridades', text: 'Cuando una ley o una autoridad competente nos lo requiera de forma fundada y motivada.' },
      ]} />
      <p>Estas comunicaciones no requieren tu consentimiento porque son necesarias para cumplir la relación contigo o una obligación legal.</p>
    </>,
  },
  {
    id: 'derechos', label: 'Derechos ARCO', title: <>Tus derechos <i>ARCO.</i></>,
    content: <>
      <p>En cualquier momento puedes ejercer tus derechos de:</p>
      <LegalCards items={[
        { icon: <Eye size={18} />, title: 'Acceso', text: 'Saber qué datos tuyos tenemos y cómo los usamos.' },
        { icon: <PenLine size={18} />, title: 'Rectificación', text: 'Corregirlos si son inexactos o están incompletos.' },
        { icon: <Trash2 size={18} />, title: 'Cancelación', text: 'Pedir que los eliminemos cuando ya no sean necesarios.' },
        { icon: <Hand size={18} />, title: 'Oposición', text: 'Oponerte a un uso concreto, o revocar tu consentimiento.' },
      ]} />
      <p>Envía tu solicitud a <a href={`mailto:${CONTACT}`}>{CONTACT}</a> con tu nombre, el correo de tu cuenta, una copia de tu identificación y una descripción clara de lo que pides. Te responderemos en un plazo máximo de 20 días hábiles. Si no quedas satisfecho, puedes acudir a la autoridad de protección de datos competente.</p>
    </>,
  },
  {
    id: 'seguridad', label: 'Conservación y seguridad', title: <>Conservación y <i>seguridad.</i></>,
    content: <>
      <p>Conservamos tus datos mientras tengas una cuenta o mientras sean necesarios para tus pedidos y nuestras obligaciones legales y fiscales. Después los eliminamos o los anonimizamos.</p>
      <LegalCards items={[
        { icon: <Lock size={18} />, title: 'Conexión cifrada', text: 'El sitio en producción solo funciona sobre HTTPS.' },
        { icon: <ShieldCheck size={18} />, title: 'Sesiones protegidas', text: 'Cookies HttpOnly que el código del navegador no puede leer, con cierre tras 30 minutos de inactividad.' },
        { icon: <Gauge size={18} />, title: 'Límites de uso', text: 'Freno automático a intentos repetidos de inicio de sesión y registro.' },
        { icon: <Database size={18} />, title: 'Acceso mínimo', text: 'Solo el personal autorizado accede a los pedidos, y cada acción administrativa queda registrada.' },
      ]} />
    </>,
  },
  {
    id: 'cookies', label: 'Cookies', title: <>Cookies y <i>almacenamiento.</i></>,
    content: <>
      <p>Usamos únicamente cookies técnicas, imprescindibles para que la bolsa, la sesión y la protección de formularios funcionen. No usamos cookies de análisis ni de publicidad.</p>
      <p><Link className="text-link" href="/policies/cookies"><Cookie size={16} />Consulta el aviso de cookies</Link></p>
    </>,
  },
  {
    id: 'aviso-legal', label: 'Aviso legal', title: <>Aviso <i>legal.</i></>,
    content: <>
      <p><strong>Titular del sitio.</strong> FORMA. es un proyecto de demostración sin actividad comercial. Los productos, precios, proveedores e inventarios son ficticios y no constituyen una oferta.</p>
      <p><strong>Propiedad intelectual.</strong> El diseño, los textos y la marca FORMA. pertenecen a sus autores. Las fotografías son ilustrativas y proceden de Unsplash conforme a su licencia.</p>
      <p><strong>Responsabilidad.</strong> Hacemos lo posible por mantener el sitio disponible y correcto, pero no garantizamos que funcione sin interrupciones ni errores. Los enlaces a sitios de terceros se rigen por sus propias condiciones.</p>
      <p><strong>Ley aplicable.</strong> Este aviso se rige por las leyes de los Estados Unidos Mexicanos. Consulta también los <Link href="/policies/terms">términos de uso</Link>.</p>
    </>,
  },
  {
    id: 'cambios', label: 'Cambios', title: <>Cambios a este <i>aviso.</i></>,
    content: <>
      <p>Podemos actualizar este aviso para reflejar cambios legales o del servicio. Publicaremos siempre la versión vigente en esta página, con su fecha de actualización. Si el cambio es importante, te lo indicaremos en el sitio.</p>
    </>,
  },
];

export default function Page() {
  return <LegalDocument
    eyebrow="Información legal"
    title={[<>Aviso de</>, <i key="i">privacidad.</i>]}
    intro="Qué datos tuyos tratamos, para qué y cómo puedes controlarlos. Lo hemos escrito para que se entienda a la primera."
    updated="12 nov 2026"
    readingTime="5 min"
    summary={[
      { icon: <Database size={18} />, title: 'Lo justo', text: 'Solo los datos para comprar y recibir tu pedido.' },
      { icon: <Target size={18} />, title: 'Un solo fin', text: 'Gestionar tu cuenta y tus pedidos. Sin publicidad.' },
      { icon: <Share2 size={18} />, title: 'Sin venta', text: 'Solo los compartimos con el procesador de pagos.' },
      { icon: <ShieldCheck size={18} />, title: 'Tú decides', text: 'Accede, corrige o elimina tus datos cuando quieras.' },
    ]}
    sections={sections}
    related={[{ href: '/policies/cookies', label: 'Aviso de cookies' }, { href: '/policies/terms', label: 'Términos de uso' }]}
  />;
}
