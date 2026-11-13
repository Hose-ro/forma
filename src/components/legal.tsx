'use client';
import Link from 'next/link';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { motion, useScroll, useSpring, AnimatePresence } from 'motion/react';
import { ArrowUp, ArrowUpRight, Info } from 'lucide-react';
import { Reveal } from './ui';

const EASE = [.22, 1, .36, 1] as const;

export type LegalSection = { id: string; label: string; title: ReactNode; content: ReactNode };

type Props = {
  eyebrow: string;
  title: ReactNode[];
  intro: ReactNode;
  updated: string;
  readingTime: string;
  summary?: { icon: ReactNode; title: string; text: string }[];
  sections: LegalSection[];
  related: { href: string; label: string }[];
};

export function LegalDocument({ eyebrow, title, intro, updated, readingTime, summary, sections, related }: Props) {
  const [active, setActive] = useState(sections[0]?.id);
  const [showTop, setShowTop] = useState(false);
  const chips = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll();
  const progress = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: .3 });

  useEffect(() => {
    const visible = new Map<string, boolean>();
    const io = new IntersectionObserver(entries => {
      for (const e of entries) visible.set(e.target.id, e.isIntersecting);
      const first = sections.find(s => visible.get(s.id));
      if (first) setActive(first.id);
    }, { rootMargin: '-30% 0px -60% 0px' });
    for (const s of sections) { const el = document.getElementById(s.id); if (el) io.observe(el); }
    const onScroll = () => setShowTop(window.scrollY > 900);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => { io.disconnect(); window.removeEventListener('scroll', onScroll); };
  }, [sections]);

  useEffect(() => {
    const bar = chips.current;
    const chip = bar?.querySelector<HTMLElement>(`[data-id="${active}"]`);
    if (!bar || !chip || bar.scrollWidth <= bar.clientWidth) return;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    bar.scrollTo({ left: chip.offsetLeft - bar.clientWidth / 2 + chip.clientWidth / 2, behavior: reduced ? 'auto' : 'smooth' });
  }, [active]);

  return <article className="legal">
    <motion.div className="legal-progress" style={{ scaleX: progress }} aria-hidden />

    <header className="legal-hero">
      <div>
        <motion.p className="eyebrow" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: .6 }}>{eyebrow}</motion.p>
        <h1>{title.map((line, i) => <span className="legal-line" key={i}>
          <motion.span initial={{ y: '110%' }} animate={{ y: 0 }} transition={{ duration: .9, delay: .1 + i * .09, ease: EASE }}>{line}</motion.span>
        </span>)}</h1>
      </div>
      <motion.div className="legal-hero-aside" initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: .8, delay: .35, ease: EASE }}>
        <p>{intro}</p>
        <dl className="legal-meta">
          <div><dt>Actualizado</dt><dd>{updated}</dd></div>
          <div><dt>Lectura</dt><dd>{readingTime}</dd></div>
        </dl>
      </motion.div>
    </header>

    <motion.aside className="legal-demo" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: .7, delay: .5, ease: EASE }}>
      <Info size={17} aria-hidden />
      <p><strong>FORMA. es una tienda de demostración.</strong> No introduzcas datos reales. Este texto es un modelo: debe revisarlo un profesional antes de usarse en una tienda que opere de verdad.</p>
    </motion.aside>

    {summary && <section className="legal-summary" aria-label="En breve">
      {summary.map((s, i) => <Reveal key={s.title} delay={i * .07}>
        <div className="legal-summary-item"><span className="legal-summary-icon">{s.icon}</span><h3>{s.title}</h3><p>{s.text}</p></div>
      </Reveal>)}
    </section>}

    <nav className="legal-chips" aria-label="Secciones">
      <div ref={chips}>
        {sections.map((s, i) => <a key={s.id} href={`#${s.id}`} data-id={s.id} aria-current={active === s.id ? 'true' : undefined}>
          <span>{String(i + 1).padStart(2, '0')}</span>{s.label}
        </a>)}
      </div>
    </nav>

    <div className="legal-body">
      <nav className="legal-toc" aria-label="Contenido">
        <p className="eyebrow">Contenido</p>
        <ol>
          {sections.map((s, i) => <li key={s.id}>
            <a href={`#${s.id}`} aria-current={active === s.id ? 'true' : undefined}>
              {active === s.id && <motion.span className="legal-toc-marker" layoutId="legal-toc-marker" transition={{ type: 'spring', stiffness: 380, damping: 34 }} />}
              <span className="legal-toc-num">{String(i + 1).padStart(2, '0')}</span>{s.label}
            </a>
          </li>)}
        </ol>
      </nav>

      <div className="legal-content">
        {sections.map((s, i) => <section key={s.id} id={s.id} className="legal-section" aria-labelledby={`${s.id}-title`}>
          <Reveal className="legal-section-head">
            <span className="legal-num">{String(i + 1).padStart(2, '0')}</span>
            <h2 id={`${s.id}-title`}>{s.title}</h2>
          </Reveal>
          <motion.div className="legal-rule" initial={{ scaleX: 0 }} whileInView={{ scaleX: 1 }} viewport={{ once: true, amount: 1 }} transition={{ duration: 1.1, ease: EASE }} />
          <Reveal className="legal-prose" delay={.08}>{s.content}</Reveal>
        </section>)}

        <Reveal className="legal-related">
          <p className="eyebrow">Sigue leyendo</p>
          {related.map(r => <Link key={r.href} href={r.href}>{r.label}<ArrowUpRight size={26} /></Link>)}
        </Reveal>
      </div>
    </div>

    <AnimatePresence>
      {showTop && <motion.button key="top" className="legal-top" aria-label="Volver arriba"
        initial={{ opacity: 0, scale: .6 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: .6 }} transition={{ duration: .3, ease: EASE }}
        onClick={() => window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })}>
        <ArrowUp size={18} />
      </motion.button>}
    </AnimatePresence>
  </article>;
}

export function LegalCards({ items }: { items: { icon?: ReactNode; title: string; text: ReactNode }[] }) {
  return <div className="legal-cards">
    {items.map((it, i) => <motion.div key={it.title} className="legal-card"
      initial={{ opacity: 0, y: 20 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, amount: .3 }} transition={{ duration: .6, delay: i * .07, ease: EASE }}>
      {it.icon && <span className="legal-card-icon">{it.icon}</span>}
      <h3>{it.title}</h3>
      <p>{it.text}</p>
    </motion.div>)}
  </div>;
}

export type StorageRow = { name: string; owner: string; purpose: string; duration: string; kind: string };

export function StorageTable({ rows, caption }: { rows: StorageRow[]; caption: string }) {
  return <div className="legal-table-wrap">
    <table className="legal-table">
      <caption className="sr-only">{caption}</caption>
      <thead><tr><th scope="col">Nombre</th><th scope="col">Finalidad</th><th scope="col">Duración</th><th scope="col">Tipo</th></tr></thead>
      <tbody>
        {rows.map((r, i) => <motion.tr key={r.name} initial={{ opacity: 0, x: -12 }} whileInView={{ opacity: 1, x: 0 }} viewport={{ once: true, amount: .5 }} transition={{ duration: .5, delay: i * .06, ease: EASE }}>
          <th scope="row" data-label="Nombre"><code>{r.name}</code><small>{r.owner}</small></th>
          <td data-label="Finalidad">{r.purpose}</td>
          <td data-label="Duración">{r.duration}</td>
          <td data-label="Tipo"><span className="legal-tag">{r.kind}</span></td>
        </motion.tr>)}
      </tbody>
    </table>
  </div>;
}

export function LegalQuote({ children }: { children: ReactNode }) {
  return <blockquote className="legal-quote">{children}</blockquote>;
}

/* ---------- Aviso de cookies (banner) ---------- */

const NOTICE_KEY = 'forma-cookie-notice-v1';
const NOTICE_EVENT = 'forma:cookie-notice';

export function CookieNotice() {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let seen = false;
    try { seen = localStorage.getItem(NOTICE_KEY) === 'seen'; } catch { /* storage unavailable */ }
    const timer = seen ? undefined : window.setTimeout(() => setOpen(true), 1400);
    const reopen = () => setOpen(true);
    window.addEventListener(NOTICE_EVENT, reopen);
    return () => { window.clearTimeout(timer); window.removeEventListener(NOTICE_EVENT, reopen); };
  }, []);
  const accept = () => {
    try { localStorage.setItem(NOTICE_KEY, 'seen'); } catch { /* storage unavailable */ }
    setOpen(false);
  };
  return <AnimatePresence>
    {open && <motion.section key="cookie" className="cookie-notice" role="region" aria-label="Aviso de cookies"
      initial={{ opacity: 0, y: 40 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 40 }} transition={{ duration: .55, ease: EASE }}>
      <motion.span className="cookie-notice-mark" aria-hidden initial={{ rotate: -90, scale: .4 }} animate={{ rotate: 0, scale: 1 }} transition={{ duration: .8, delay: .15, ease: EASE }}>✳</motion.span>
      <div>
        <h2>Solo cookies <i>esenciales.</i></h2>
        <p>Usamos cookies técnicas para mantener tu bolsa y tu sesión. Sin publicidad ni rastreo.</p>
        <div className="cookie-notice-actions">
          <button type="button" onClick={accept}>Entendido</button>
          <Link href="/policies/cookies" onClick={accept}>Ver detalles <ArrowUpRight size={14} /></Link>
        </div>
      </div>
    </motion.section>}
  </AnimatePresence>;
}

export function ShowCookieNotice() {
  const [done, setDone] = useState(false);
  return <button type="button" className="text-link legal-inline-button" onClick={() => {
    try { localStorage.removeItem(NOTICE_KEY); } catch { /* storage unavailable */ }
    window.dispatchEvent(new Event(NOTICE_EVENT));
    setDone(true);
  }}>{done ? 'Aviso mostrado' : 'Volver a mostrar el aviso de cookies'}<ArrowUpRight size={16} /></button>;
}
