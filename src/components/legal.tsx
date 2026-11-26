'use client';
import { BUSINESS, LIVE } from '@/lib/business';
import { THEME_STORAGE_KEY } from '@/lib/theme';
import Link from 'next/link';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { usePathname } from 'next/navigation';
import { setMarketingConsent, useMarketingConsent } from '@/lib/marketing/tracking';
import { AnimatePresence, motion, useReducedMotion, useScroll, useSpring } from 'motion/react';
import { ArrowUpRight, ChartColumn, Compass, Cookie, CreditCard, EyeOff, FileText, Globe, HardDrive, KeyRound, Lock, Mail, Monitor, PackageCheck, RotateCcw, Settings2, ShieldCheck, Type, UserRound, X, type LucideIcon } from 'lucide-react';
import { Reveal } from './ui';

const EASE = [0.22, 1, 0.36, 1] as const;
const UPDATED = '17 de noviembre de 2026';
const CONTACT = BUSINESS.email;

const SECTIONS = [
  { id: 'responsable', label: 'Responsable' },
  { id: 'datos', label: 'Datos que tratamos' },
  { id: 'finalidades', label: 'Finalidades' },
  { id: 'cookies', label: 'Cookies' },
  { id: 'transferencias', label: 'Transferencias' },
  { id: 'derechos', label: 'Tus derechos' },
  { id: 'conservacion', label: 'Conservación y seguridad' },
  { id: 'aviso-legal', label: 'Aviso legal' },
  { id: 'cambios', label: 'Cambios' },
] as const;

const SUMMARY: SummaryItem[] = [
  { icon: UserRound, title: 'Lo mínimo', text: 'Solo pedimos lo necesario para tu cuenta y para entregar un pedido.' },
  { icon: ShieldCheck, title: 'Publicidad opcional', text: 'Tú decides si permites que midamos la eficacia de los anuncios de TikTok.' },
  { icon: Lock, title: 'Sin tarjetas', text: 'Los pagos los procesa la plataforma del método que elijas. Nunca vemos los datos de tu tarjeta ni de tu cuenta.' },
  { icon: KeyRound, title: 'Tú decides', text: 'Puedes acceder, corregir o eliminar tus datos cuando quieras.' },
];

const DATA: { icon: LucideIcon; title: string; items: string[] }[] = [
  { icon: UserRound, title: 'Identificación y contacto', items: ['Nombre y apellidos', 'Correo electrónico', 'Teléfono'] },
  { icon: PackageCheck, title: 'Entrega', items: ['Calle y número', 'Ciudad y estado', 'Código postal y país'] },
  { icon: KeyRound, title: 'Cuenta', items: ['Correo de acceso', 'Contraseña cifrada con BCrypt, nunca en texto plano', 'Rol de la cuenta'] },
  { icon: FileText, title: 'Pedidos y pagos', items: ['Artículos, importes e historial', 'Estado e identificador del pago', 'Sin números de tarjeta'] },
];

const COOKIES = [
  { name: 'OLVENIO_SESSION', purpose: 'Mantiene tu sesión iniciada.', duration: 'Expira tras 30 min sin actividad' },
  { name: 'OLVENIO_GUEST', purpose: 'Recuerda tu bolsa si compras sin cuenta.', duration: '90 días' },
  { name: 'XSRF-TOKEN', purpose: 'Protege los formularios frente a solicitudes falsificadas.', duration: 'Sesión' },
];

const RIGHTS = [
  { letter: 'A', title: 'Acceso', text: 'Saber qué datos tuyos tenemos y cómo los usamos.' },
  { letter: 'R', title: 'Rectificación', text: 'Corregirlos si están incompletos o son inexactos.' },
  { letter: 'C', title: 'Cancelación', text: 'Pedir que los eliminemos cuando ya no sean necesarios.' },
  { letter: 'O', title: 'Oposición', text: 'Negarte a un uso concreto de tu información.' },
];

function Section({ id, index, title, children }: { id: string; index: number; title: ReactNode; children: ReactNode }) {
  const reduced = useReducedMotion();
  return (
    <section id={id} className="legal-section" aria-labelledby={`${id}-title`}>
      <div className="legal-section-head">
        <span className="legal-index" aria-hidden="true">{String(index).padStart(2, '0')}</span>
        <motion.span
          className="legal-rule"
          aria-hidden="true"
          initial={reduced ? false : { scaleX: 0 }}
          whileInView={{ scaleX: 1 }}
          viewport={{ once: true, amount: 1 }}
          transition={{ duration: 1, ease: EASE }}
        />
      </div>
      <Reveal>
        <h2 id={`${id}-title`}>{title}</h2>
      </Reveal>
      <Reveal delay={0.08} className="legal-body">{children}</Reveal>
    </section>
  );
}

type NavItem = { id: string; label: string };
type SummaryItem = { icon: LucideIcon; title: string; text: string };

function LegalDocument({ lines, intro, reading, sections, summary, children }: {
  lines: ReactNode[]; intro: ReactNode; reading: string; sections: readonly NavItem[]; summary: SummaryItem[]; children: ReactNode;
}) {
  const reduced = useReducedMotion();
  const [active, setActive] = useState<string>(sections[0].id);
  const chips = useRef<HTMLElement>(null);
  const { scrollYProgress } = useScroll();
  const progress = useSpring(scrollYProgress, { stiffness: 140, damping: 30, restDelta: 0.001 });

  useEffect(() => {
    // Active = last section whose heading has crossed 40% of the viewport (or the last one at page end).
    let frame = 0;
    const update = () => {
      frame = 0;
      const line = window.innerHeight * 0.4;
      const atEnd = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4;
      let current: string = sections[0].id;
      for (const s of sections) {
        const node = document.getElementById(s.id);
        if (node && node.getBoundingClientRect().top <= line) current = s.id;
      }
      setActive(atEnd ? sections[sections.length - 1].id : current);
    };
    const schedule = () => { if (!frame) frame = requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
    };
  }, [sections]);

  // Keep the active chip visible in the mobile index without moving the page vertically.
  useEffect(() => {
    const bar = chips.current;
    const chip = bar?.querySelector<HTMLElement>(`[data-id="${active}"]`);
    if (!bar || !chip || bar.scrollWidth <= bar.clientWidth) return;
    bar.scrollTo({ left: chip.offsetLeft - bar.clientWidth / 2 + chip.offsetWidth / 2, behavior: reduced ? 'auto' : 'smooth' });
  }, [active, reduced]);

  const line = (delay: number) => ({
    initial: reduced ? false : { y: '110%' },
    animate: { y: '0%' },
    transition: { duration: 0.9, delay, ease: EASE },
  });

  return (
    <article className="legal-page">
      <motion.div className="legal-progress" style={{ scaleX: progress }} aria-hidden="true" />

      <header className="legal-hero">
        <motion.p className="eyebrow" initial={reduced ? false : { opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.6 }}>
          Información legal
        </motion.p>
        <h1>
          {lines.map((l, i) => (
            <span className="legal-line" key={i}><motion.span {...line(0.05 + i * 0.1)}>{l}</motion.span></span>
          ))}
        </h1>
        <motion.div
          className="legal-hero-meta"
          initial={reduced ? false : { opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: 0.35, ease: EASE }}
        >
          <p>{intro}</p>
          <dl>
            <div><dt>Actualizado</dt><dd>{UPDATED}</dd></div>
            <div><dt>Lectura</dt><dd>{reading}</dd></div>
          </dl>
        </motion.div>
      </header>

      <motion.aside
        className="legal-notice"
        role="note"
        initial={reduced ? false : { opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.7, delay: 0.5, ease: EASE }}
      >
        <span className="little-cross" aria-hidden="true">{'✳︎'}</span>
        {LIVE ? (
          <p>
            <strong>Tus datos, bajo tu control.</strong> Solo usamos tu información para procesar tus pedidos y atenderte. Escríbenos a{' '}
            {CONTACT} para cualquier duda sobre este aviso.
          </p>
        ) : (
          <p>
            <strong>Tienda de demostración.</strong> Olvenio no vende productos ni cobra pagos reales. Usa siempre datos ficticios.
            Este texto es un modelo de referencia: revísalo con un especialista antes de usarlo en una tienda en operación.
          </p>
        )}
      </motion.aside>

      <div className="legal-summary">
        {summary.map((s, i) => (
          <Reveal key={s.title} delay={i * 0.07} className="legal-summary-card">
            <s.icon size={20} strokeWidth={1.5} aria-hidden="true" />
            <h3>{s.title}</h3>
            <p>{s.text}</p>
          </Reveal>
        ))}
      </div>

      <nav className="legal-chips" aria-label="Secciones del aviso" ref={chips}>
        {sections.map((s, i) => (
          <a key={s.id} href={`#${s.id}`} data-id={s.id} aria-current={active === s.id ? 'true' : undefined}>
            <span>{String(i + 1).padStart(2, '0')}</span>{s.label}
          </a>
        ))}
      </nav>

      <div className="legal-layout">
        <nav className="legal-toc" aria-label="Índice del aviso">
          <p className="eyebrow">Índice</p>
          <ol>
            {sections.map((s, i) => (
              <li key={s.id}>
                <a href={`#${s.id}`} aria-current={active === s.id ? 'true' : undefined}>
                  {active === s.id && (
                    <motion.i className="legal-toc-marker" layoutId="legal-toc-marker" transition={{ type: 'spring', stiffness: 380, damping: 32 }} />
                  )}
                  <span>{String(i + 1).padStart(2, '0')}</span>
                  {s.label}
                </a>
              </li>
            ))}
          </ol>
          <a className="text-link" href={`mailto:${CONTACT}`}>Escríbenos <ArrowUpRight size={16} /></a>
        </nav>

        <div className="legal-content">
          {children}
        </div>
      </div>
    </article>
  );
}

export function PrivacyNotice({ googleEnabled = false }: { googleEnabled?: boolean }) {
  return (
    <LegalDocument
      lines={['Aviso de', <i key="i">privacidad.</i>]}
      intro="Cómo Olvenio trata la información que compartes al navegar, crear una cuenta o hacer un pedido. Sin letra pequeña: lo que recabamos, para qué y cómo puedes decidir sobre ello."
      reading="6 minutos"
      sections={SECTIONS}
      summary={SUMMARY}
    >
            <Section id="responsable" index={1} title={<>Quién es <i>responsable.</i></>}>
              <p>
                Olvenio es responsable del tratamiento de los datos personales que se recaban en este sitio, de conformidad con la
                Ley Federal de Protección de Datos Personales en Posesión de los Particulares vigente en México y su normativa aplicable.
              </p>
              <dl className="legal-facts">
                <div><dt>Titular</dt><dd>{BUSINESS.legalName}{BUSINESS.taxId && <> · RFC {BUSINESS.taxId}</>}</dd></div>
                <div><dt>Contacto de privacidad</dt><dd><a href={`mailto:${CONTACT}`}>{CONTACT}</a></dd></div>
                <div><dt>Domicilio</dt><dd>{BUSINESS.address}</dd></div>
              </dl>
            </Section>

            <Section id="datos" index={2} title={<>Qué datos <i>tratamos.</i></>}>
              <p>Para gestionar tu cuenta y tus pedidos, tratamos los datos que nos proporcionas:</p>
              <div className="legal-data-grid">
                {DATA.map((d, i) => (
                  <Reveal key={d.title} delay={i * 0.06} className="legal-data-card">
                    <d.icon size={18} strokeWidth={1.5} aria-hidden="true" />
                    <h3>{d.title}</h3>
                    <ul>{d.items.map((it) => <li key={it}>{it}</li>)}</ul>
                  </Reveal>
                ))}
              </div>
              {googleEnabled && <p>
                Si eliges continuar con Google, recibimos un identificador de tu cuenta de Google, tu correo y, cuando
                están disponibles, tu nombre y apellidos. Guardamos el identificador para reconocer tu cuenta vinculada.
              </p>}
              <p>
                Si solicitas factura, guardamos los datos fiscales que ingreses y los archivos PDF y XML correspondientes.
                Por seguridad, procesamos tu dirección IP de forma temporal para limitar intentos de acceso abusivos; no se guarda en tu
                perfil. <strong>No solicitamos datos personales sensibles</strong> ni información de menores de edad.
              </p>
            </Section>

            <Section id="finalidades" index={3} title={<>Para qué los <i>usamos.</i></>}>
              <div className="legal-columns">
                <div>
                  <h3>Finalidades necesarias</h3>
                  <ul className="legal-list">
                    <li>Crear y administrar tu cuenta.</li>
                    <li>Procesar, confirmar y dar seguimiento a tus pedidos.</li>
                    <li>Mostrar tu historial de compras.</li>
                    <li>Proteger el sitio frente a fraudes y accesos no autorizados.</li>
                    <li>Atender cambios, devoluciones y aclaraciones, y gestionar tu factura cuando la solicites.</li>
                  </ul>
                </div>
                <div>
                  <h3>Finalidades secundarias</h3>
                  <p>Si aceptas, medimos con TikTok si los anuncios generan visitas, búsquedas, registros o compras. Usamos identificadores publicitarios, eventos de la tienda y datos comerciales como artículos, cantidades e importes.</p>
                  <p>El seguimiento es opcional. Puedes cambiar tu elección en el <Link className="legal-inline-link" href="/policies/cookies#gestionar">Aviso de cookies</Link>.</p>
                </div>
              </div>
            </Section>

            <Section id="cookies" index={4} title={<>Cookies y <i>preferencias.</i></>}>
              <p>
                Las cookies técnicas mantienen la sesión, la bolsa y la seguridad de los formularios. Si aceptas la medición publicitaria,
                podremos activar TikTok Pixel y compartir eventos con TikTok Events API.
              </p>
              {googleEnabled && <p>En Mi cuenta, Google Identity Services puede usar cookies propias para el acceso opcional con Google.</p>}
              <div className="legal-cookies" role="table" aria-label="Cookies que utiliza el sitio">
                <div role="row" className="legal-cookies-head">
                  <span role="columnheader">Cookie</span><span role="columnheader">Para qué sirve</span><span role="columnheader">Duración</span>
                </div>
                {COOKIES.map((c, i) => (
                  <Reveal key={c.name} delay={i * 0.06}>
                    <div role="row" className="legal-cookie-row">
                      <span role="cell" className="legal-cookie-name"><Cookie size={14} strokeWidth={1.5} aria-hidden="true" /><code>{c.name}</code></span>
                      <span role="cell">{c.purpose}</span>
                      <span role="cell" className="muted">{c.duration}</span>
                    </div>
                  </Reveal>
                ))}
              </div>
              <p className="muted">Puedes borrarlas desde la configuración de tu navegador; al hacerlo cerrarás sesión y se vaciará la bolsa de invitado. Encontrarás todos los detalles en el{' '}<Link className="legal-inline-link" href="/policies/cookies">Aviso de cookies</Link>.</p>
            </Section>

            <Section id="transferencias" index={5} title={<>Con quién los <i>compartimos.</i></>}>
              <p>No vendemos ni rentamos tus datos. Compartimos los datos necesarios para cumplir tu pedido o la ley y, con tu consentimiento, para medir anuncios.</p>
              <ul className="legal-list">
                <li>
                  <strong>El procesador del método de pago que elijas</strong>: Mercado Pago, Stripe (tarjeta y OXXO), PayPal o Conekta
                  (transferencia SPEI), según los métodos disponibles al pagar. Les enviamos el importe y la referencia de tu pedido; a Conekta,
                  además, tu nombre, correo y, si lo diste, tu teléfono, que necesita para emitir tu referencia de pago. Tus datos de tarjeta o
                  de cuenta se capturan directamente en su plataforma y nunca pasan por nuestros servidores. Si cambias de método antes de
                  pagar, el procesador anterior solo conserva el intento cancelado.{LIVE ? '' : ' En esta demostración operan solo en modo de prueba.'}
                </li>
                <li><strong>Postali</strong> recibe el código postal para consultar las colonias y verificar el municipio y estado.</li>
                <li><strong>El operador logístico</strong> recibe los datos de contacto y domicilio que necesita para cotizar o entregar el paquete.</li>
                <li><strong>El proveedor de facturación</strong> recibe tus datos fiscales cuando solicitas un CFDI.</li>
                <li><strong>Autoridades competentes</strong>, cuando exista un requerimiento fundado y motivado.</li>
              </ul>
              <p>Con tu autorización, compartimos con <strong>TikTok</strong> eventos de la tienda, identificadores de clic o de cookie, páginas públicas y datos comerciales. El píxel recibe los datos de conexión del navegador. Excluimos correo, teléfono, direcciones y datos de pago de la medición.</p>
            </Section>

            <Section id="derechos" index={6} title={<>Tus derechos <i>ARCO.</i></>}>
              <div className="legal-rights">
                {RIGHTS.map((r, i) => (
                  <Reveal key={r.letter} delay={i * 0.07} className="legal-right">
                    <span className="legal-right-letter" aria-hidden="true">{r.letter}</span>
                    <h3>{r.title}</h3>
                    <p>{r.text}</p>
                  </Reveal>
                ))}
              </div>
              <p>
                También puedes <strong>revocar tu consentimiento</strong> o limitar el uso de tus datos. Escribe a{' '}
                <a className="legal-inline-link" href={`mailto:${CONTACT}`}>{CONTACT}</a> indicando tu nombre, el derecho que quieres
                ejercer y un medio para acreditar tu identidad. Te responderemos en un máximo de 20 días hábiles.
              </p>
              <p className="muted">
                Si consideras que tu derecho a la protección de datos ha sido vulnerado, puedes acudir ante la autoridad de protección
                de datos personales competente en México.
              </p>
            </Section>

            <Section id="conservacion" index={7} title={<>Cuánto tiempo y cómo los <i>protegemos.</i></>}>
              <p>
                Conservamos tus datos mientras tu cuenta esté activa y, después, solo durante el tiempo necesario para cumplir obligaciones
                legales, fiscales o de aclaración de pedidos. Pasado ese plazo, se eliminan o anonimizan.
              </p>
              <ul className="legal-list legal-list-check">
                <li>Conexión cifrada (HTTPS) en todo el sitio.</li>
                <li>Contraseñas guardadas con hash BCrypt.</li>
                <li>Cookies de sesión HttpOnly y protección CSRF.</li>
                <li>Acceso administrativo restringido por rol y verificado en el servidor.</li>
                <li>Límites de intentos para inicio de sesión y registro.</li>
              </ul>
            </Section>

            <Section id="aviso-legal" index={8} title={<>Aviso <i>legal.</i></>}>
              <p>
                {LIVE
                  ? `Este sitio es operado por ${BUSINESS.legalName}, con domicilio en ${BUSINESS.address}. Las compras realizadas aquí se rigen por los Términos y condiciones y por la política de envíos y devoluciones publicados en este sitio.`
                  : 'Este sitio es un proyecto de demostración. Los productos, precios, inventarios y proveedores son ficticios: crear un pedido no constituye una compra, no genera cargos y no da lugar a entregas.'}
              </p>
              <p>
                El diseño, los textos y la marca Olvenio pertenecen a sus autores. {LIVE ? 'Las fotografías son de referencia.' : 'Las fotografías son ilustrativas y provienen de Unsplash bajo su licencia.'} Queda prohibida la reproducción del contenido con fines comerciales sin autorización.
              </p>
              <p>
                Olvenio no se hace responsable del uso que se dé a la información ingresada en contra de estas indicaciones. Este aviso se
                rige por las leyes de los Estados Unidos Mexicanos. Consulta también los{' '}
                <Link className="legal-inline-link" href="/policies/terms">Términos de uso</Link> y la política de{' '}
                <Link className="legal-inline-link" href="/policies/shipping">Envíos y devoluciones</Link>.
              </p>
            </Section>

            <Section id="cambios" index={9} title={<>Cambios a este <i>aviso.</i></>}>
              <p>
                Podemos actualizar este aviso para reflejar cambios legales o en nuestros servicios. Publicaremos cualquier modificación en
                esta misma página, con su fecha de actualización.
              </p>
              <div className="legal-closing">
                <Mail size={20} strokeWidth={1.5} aria-hidden="true" />
                <div>
                  <p>¿Tienes dudas sobre tu privacidad?</p>
                  <a className="text-link" href={`mailto:${CONTACT}`}>{CONTACT} <ArrowUpRight size={16} /></a>
                </div>
              </div>
            </Section>
    </LegalDocument>
  );
}

/* ---------- Aviso de cookies ---------- */

const COOKIE_SECTIONS = [
  { id: 'que-son', label: 'Qué son' },
  { id: 'cookies-usadas', label: 'Cookies que usamos' },
  { id: 'almacenamiento', label: 'Almacenamiento local' },
  { id: 'no-usamos', label: 'Lo que no usamos' },
  { id: 'terceros', label: 'Sitios de terceros' },
  { id: 'gestionar', label: 'Cómo gestionarlas' },
  { id: 'cambios-cookies', label: 'Cambios' },
] as const;

const COOKIE_SUMMARY: SummaryItem[] = [
  { icon: Cookie, title: 'Cookies esenciales', text: 'Mantienen tu sesión, tu bolsa y tus formularios.' },
  { icon: EyeOff, title: 'Publicidad opcional', text: 'El píxel de TikTok se activa si lo autorizas.' },
  { icon: Settings2, title: 'Bajo tu control', text: 'Puedes borrarlas cuando quieras desde tu navegador.' },
];

const COOKIE_DETAILS = [
  { name: 'OLVENIO_SESSION', purpose: 'Mantiene tu sesión iniciada mientras navegas por tu cuenta y tus pedidos.', duration: 'Caduca tras 30 min sin actividad', flags: ['Técnica', 'Propia', 'HttpOnly'] },
  { name: 'OLVENIO_GUEST', purpose: 'Recuerda tu bolsa de compra cuando compras sin iniciar sesión.', duration: '90 días', flags: ['Técnica', 'Propia', 'HttpOnly', 'SameSite=Lax'] },
  { name: 'XSRF-TOKEN', purpose: 'Comprueba que los formularios que envías salen de este sitio y no de uno malicioso.', duration: 'Hasta cerrar el navegador', flags: ['Técnica', 'Propia', 'Seguridad'] },
];

const STORAGE = [
  { name: THEME_STORAGE_KEY, purpose: 'Recuerda si elegiste el tema claro u oscuro.', duration: 'Hasta que la borres' },
  { name: 'olvenio-prefs-v1', purpose: 'Guarda la preferencia del mensaje que aparece en el pie de página.', duration: 'Hasta que la borres' },
  { name: 'olvenio-cookie-notice-v1', purpose: 'Recuerda que ya cerraste el aviso de cookies para no volver a mostrarlo.', duration: 'Hasta que la borres' },
];

const NOT_USED: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: ChartColumn, title: 'Analítica', text: 'No medimos tu navegación con Google Analytics ni herramientas similares.' },
  { icon: Type, title: 'Servicios externos', text: 'Las tipografías e imágenes se sirven desde nuestro dominio.' },
];

const BROWSERS: { icon: LucideIcon; name: string; path: string }[] = [
  { icon: Globe, name: 'Chrome', path: 'Configuración › Privacidad y seguridad › Cookies de terceros y datos de sitios' },
  { icon: Compass, name: 'Safari', path: 'Ajustes › Privacidad › Gestionar datos de sitios web' },
  { icon: Globe, name: 'Firefox', path: 'Ajustes › Privacidad y seguridad › Cookies y datos del sitio' },
  { icon: Monitor, name: 'Edge', path: 'Configuración › Cookies y permisos del sitio' },
];

export function CookiePolicy({ googleEnabled = false }: { googleEnabled?: boolean }) {
  const reduced = useReducedMotion();
  return (
    <LegalDocument
      lines={['Aviso de', <i key="i">cookies.</i>]}
      intro="Qué guarda Olvenio en tu navegador, para qué sirve cada cosa y cómo puedes borrarlo. Spoiler: solo lo imprescindible para que la tienda funcione."
      reading="4 minutos"
      sections={COOKIE_SECTIONS}
      summary={COOKIE_SUMMARY}
    >
      <Section id="que-son" index={1} title={<>Qué son las <i>cookies.</i></>}>
        <p>
          Las cookies son pequeños archivos de texto que un sitio guarda en tu navegador para recordar información entre una página y
          otra. Sin ellas, por ejemplo, tu bolsa se vaciaría cada vez que cambias de página.
        </p>
        <p>
          Algunas son <strong>técnicas</strong>, imprescindibles para que el sitio funcione, y otras sirven para analizar tu
          comportamiento o medir publicidad. En Olvenio, la medición publicitaria requiere tu consentimiento.
        </p>
      </Section>

      <Section id="cookies-usadas" index={2} title={<>Las cookies que <i>usamos.</i></>}>
        <p>Estas son las cookies que la tienda usa para sus funciones esenciales.</p>
        {googleEnabled && <p>En Mi cuenta, el botón opcional de Google carga Google Identity Services, que puede usar cookies propias para autenticarte.</p>}
        <div className="cookie-cards">
          {COOKIE_DETAILS.map((c, i) => (
            <Reveal key={c.name} delay={i * 0.08} className="cookie-card">
              <div className="cookie-card-head">
                <motion.span
                  className="cookie-card-icon"
                  aria-hidden="true"
                  initial={reduced ? false : { rotate: -120, scale: 0.4 }}
                  whileInView={{ rotate: 0, scale: 1 }}
                  viewport={{ once: true }}
                  transition={{ duration: 0.9, delay: 0.1 + i * 0.08, ease: EASE }}
                >
                  <Cookie size={18} strokeWidth={1.5} />
                </motion.span>
                <code>{c.name}</code>
              </div>
              <p>{c.purpose}</p>
              <dl>
                <div><dt>Duración</dt><dd>{c.duration}</dd></div>
              </dl>
              <ul aria-label="Características">{c.flags.map((f) => <li key={f}>{f}</li>)}</ul>
            </Reveal>
          ))}
        </div>
        <p className="muted">
          <em>HttpOnly</em> significa que el código que se ejecuta en la página no puede leer la cookie, lo que la protege frente a
          scripts maliciosos. En producción, además, todas viajan solo por conexiones cifradas (HTTPS).
        </p>
      </Section>

      <Section id="almacenamiento" index={3} title={<>Almacenamiento <i>local.</i></>}>
        <p>
          También guardamos preferencias técnicas en el almacenamiento local de tu navegador. Estas preferencias
          nunca se envían a nuestros servidores.
        </p>
        <div className="legal-cookies" role="table" aria-label="Datos en almacenamiento local">
          <div role="row" className="legal-cookies-head">
            <span role="columnheader">Clave</span><span role="columnheader">Para qué sirve</span><span role="columnheader">Duración</span>
          </div>
          {STORAGE.map((c, i) => (
            <Reveal key={c.name} delay={i * 0.06}>
              <div role="row" className="legal-cookie-row">
                <span role="cell" className="legal-cookie-name"><HardDrive size={14} strokeWidth={1.5} aria-hidden="true" /><code>{c.name}</code></span>
                <span role="cell">{c.purpose}</span>
                <span role="cell" className="muted">{c.duration}</span>
              </div>
            </Reveal>
          ))}
        </div>
      </Section>

      <Section id="no-usamos" index={4} title={<>Lo que <i>no</i> usamos.</>}>
        <div className="cookie-none-grid">
          {NOT_USED.map((n, i) => (
            <Reveal key={n.title} delay={i * 0.07} className="cookie-none">
              <span className="cookie-none-icon" aria-hidden="true">
                <n.icon size={18} strokeWidth={1.5} />
                <motion.i
                  style={{ rotate: -35 }}
                  initial={reduced ? false : { scaleX: 0 }}
                  whileInView={{ scaleX: 1 }}
                  viewport={{ once: true, amount: 1 }}
                  transition={{ duration: 0.6, delay: 0.35 + i * 0.07, ease: EASE }}
                />
              </span>
              <h3><span className="sr-only">Sin </span>{n.title}</h3>
              <p>{n.text}</p>
            </Reveal>
          ))}
        </div>
      </Section>

      <Section id="terceros" index={5} title={<>Servicios de <i>terceros.</i></>}>
        <p>
          Si aceptas la medición publicitaria, TikTok Pixel puede usar cookies como <code>_ttp</code> y los identificadores de sesión <code>ttcsid</code>.
          Olvenio también puede usar el identificador de clic <code>ttclid</code>. Los eventos publicitarios se conservan en la tienda hasta 30 días.
          Tu autorización vence a los 180 días. Revocarla detiene la medición futura y elimina los identificadores publicitarios guardados por Olvenio.
        </p>
        <p>
          Guardamos tu decisión en las cookies <code>OLVENIO_MARKETING</code> o <code>OLVENIO_MARKETING_DENIED</code>.
          El navegador también guarda una señal de preferencia y, si se mide una compra, un identificador de evento temporal para evitar duplicados.
        </p>
        <div className="cookie-third">
          <CreditCard size={22} strokeWidth={1.5} aria-hidden="true" />
          <p>
            Cuando pagas, te llevamos al sitio del procesador del método que elegiste (<strong>Mercado Pago</strong>, <strong>Stripe</strong>,{' '}
            <strong>PayPal</strong> o <strong>Conekta</strong>) para completar la operación. Allí se aplican sus propias cookies y su aviso de
            privacidad, que te recomendamos consultar.{LIVE ? '' : ' En esta demostración, los pagos funcionan solo en modo de prueba.'}
          </p>
        </div>
      </Section>

      <Section id="gestionar" index={6} title={<>Cómo <i>gestionarlas.</i></>}>
        <p>Puedes ver y borrar las cookies y el almacenamiento local de Olvenio desde la configuración de tu navegador:</p>
        <div className="cookie-browsers">
          {BROWSERS.map((b, i) => (
            <Reveal key={b.name} delay={i * 0.06} className="cookie-browser">
              <b.icon size={18} strokeWidth={1.5} aria-hidden="true" />
              <div>
                <h3>{b.name}</h3>
                <p>{b.path}</p>
              </div>
            </Reveal>
          ))}
        </div>
        <p>
          Ten en cuenta que, si las borras o las bloqueas, se cerrará tu sesión, se vaciará la bolsa de invitado y algunos formularios
          dejarán de funcionar hasta que vuelvas a cargar la página.
        </p>
        <ShowCookieNotice />
      </Section>

      <Section id="cambios-cookies" index={7} title={<>Cambios a este <i>aviso.</i></>}>
        <p>
          Si cambia la finalidad de la medición publicitaria, actualizaremos este aviso. Consulta también el{' '}
          <Link className="legal-inline-link" href="/policies/privacy">Aviso de privacidad</Link>.
        </p>
        <div className="legal-closing">
          <Mail size={20} strokeWidth={1.5} aria-hidden="true" />
          <div>
            <p>¿Dudas sobre las cookies?</p>
            <a className="text-link" href={`mailto:${CONTACT}`}>{CONTACT} <ArrowUpRight size={16} /></a>
          </div>
        </div>
      </Section>
    </LegalDocument>
  );
}

/* ---------- Preferencias de cookies ---------- */

const NOTICE_KEY = 'olvenio-cookie-notice-v1';
const NOTICE_EVENT = 'olvenio:cookie-notice';

export function CookieNotice() {
  const reduced = useReducedMotion();
  const path = usePathname();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const { enabled, granted, decided, pendingRevocation } = useMarketingConsent();

  useEffect(() => {
    let seen = false;
    try { seen = localStorage.getItem(NOTICE_KEY) === 'seen'; } catch { /* storage unavailable */ }
    const timer = seen ? undefined : window.setTimeout(() => setOpen(true), 1200);
    const reopen = () => setOpen(true);
    window.addEventListener(NOTICE_EVENT, reopen);
    return () => { window.clearTimeout(timer); window.removeEventListener(NOTICE_EVENT, reopen); };
  }, []);

  useEffect(() => {
    if (!enabled || decided) return;
    const timer = window.setTimeout(() => setOpen(true), 1200);
    return () => window.clearTimeout(timer);
  }, [enabled, decided]);

  const dismiss = () => {
    try { localStorage.setItem(NOTICE_KEY, 'seen'); } catch { /* storage unavailable */ }
    setOpen(false);
  };

  async function choose(allowed: boolean) {
    setSaving(true);
    setError('');
    try {
      await setMarketingConsent(allowed);
      dismiss();
    } catch {
      setError(allowed ? 'No pudimos guardar tu autorización. Inténtalo de nuevo.'
        : 'El seguimiento se detuvo en este navegador. La revocación está pendiente de confirmación del servidor.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <AnimatePresence>
      {open && (
        <motion.aside
          key="cookie-notice"
          className="cookie-notice"
          aria-label="Aviso de cookies"
          initial={reduced ? false : { opacity: 0, y: 40, scale: 0.97 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={reduced ? { opacity: 0 } : { opacity: 0, y: 30, scale: 0.97 }}
          transition={{ duration: 0.6, ease: EASE }}
        >
          <motion.span
            className="cookie-notice-mark"
            aria-hidden="true"
            initial={reduced ? false : { rotate: -180, scale: 0.3, opacity: 0 }}
            animate={{ rotate: 0, scale: 1, opacity: 1 }}
            transition={{ duration: 0.9, delay: 0.15, ease: EASE }}
          >
            {'✳︎'}
          </motion.span>
          <div className="cookie-notice-body">
            <h2>Preferencias de <i>cookies.</i></h2>
            <p>{enabled
              ? 'Las cookies esenciales mantienen tu sesión y tu bolsa. Puedes permitir la medición de anuncios de TikTok.'
              : 'Usamos cookies técnicas para mantener tu sesión y tu bolsa.'}</p>
            <div className="cookie-notice-actions">
              {enabled ? <>
                {!granted && !pendingRevocation && <button type="button" className="button" disabled={saving}
                  onClick={() => void choose(true)}>Aceptar publicidad</button>}
                <button type="button" className="button" disabled={saving}
                  onClick={() => void choose(false)}>{pendingRevocation ? 'Reintentar revocación' : granted ? 'Revocar publicidad' : 'Rechazar publicidad'}</button>
              </> : <button type="button" className="button" onClick={dismiss}>Entendido</button>}
              {path !== '/policies/cookies' && (
                <Link href="/policies/cookies" className="text-link" onClick={dismiss}>Ver detalles <ArrowUpRight size={14} /></Link>
              )}
            </div>
            {error && <p className="error" role="status">{error}</p>}
          </div>
          <button type="button" className="cookie-notice-close" aria-label="Cerrar aviso de cookies" onClick={dismiss}><X size={16} /></button>
        </motion.aside>
      )}
    </AnimatePresence>
  );
}

function ShowCookieNotice() {
  const [shown, setShown] = useState(false);
  return (
    <button
      type="button"
      className="text-link cookie-reopen"
      onClick={() => {
        try { localStorage.removeItem(NOTICE_KEY); } catch { /* storage unavailable */ }
        window.dispatchEvent(new Event(NOTICE_EVENT));
        setShown(true);
      }}
    >
      <RotateCcw size={15} aria-hidden="true" />
      {shown ? 'Preferencias mostradas abajo' : 'Configurar cookies'}
    </button>
  );
}
