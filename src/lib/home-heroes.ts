// Home hero rotation, content types and the defaults used when the API has no content to offer.
// Kept free of path aliases so node:test can import it directly.

export const HERO_VARIANTS = ['a', 'b', 'c', 'd'] as const;
export type HeroVariant = (typeof HERO_VARIANTS)[number];

export const HERO_NAMES: Record<HeroVariant, string> = {
  a: 'Escena y mosaico', b: 'Columnas por mundo', c: 'Campaña de producto', d: 'Momentos del día',
};

/** Each hero stays up for this many hours, then the next enabled one takes over. */
export const HERO_SLOT_HOURS = 12;

/** Slots start at 00:00 and 12:00 store time, so every visitor sees the same hero at the same moment. */
export const STORE_TIME_ZONE = 'America/Mexico_City';

export type Moment = 'manana' | 'tarde' | 'noche';
export const MOMENTS: { id: Moment; label: string }[] = [
  { id: 'manana', label: 'Mañana' }, { id: 'tarde', label: 'Tarde' }, { id: 'noche', label: 'Noche' },
];

function storeClock(date: Date, timeZone: string) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23',
  }).formatToParts(date).map(part => [part.type, part.value]));
  const day = Math.floor(Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day)) / 86_400_000);
  return { day, hour: Number(parts.hour) };
}

/** The hero for this moment, cycling through `enabled` (in variant order) once per slot. */
export function heroForTime(
  date: Date = new Date(), timeZone: string = STORE_TIME_ZONE, enabled: readonly HeroVariant[] = HERO_VARIANTS,
): HeroVariant {
  const cycle = HERO_VARIANTS.filter(variant => enabled.includes(variant));
  if (!cycle.length) return 'a';
  const { day, hour } = storeClock(date, timeZone);
  const slot = day * (24 / HERO_SLOT_HOURS) + Math.floor(hour / HERO_SLOT_HOURS);
  return cycle[slot % cycle.length];
}

/** Start of the next slot as store-time hour (0 or 12), for the admin status line. */
export function nextSlotHour(date: Date = new Date(), timeZone: string = STORE_TIME_ZONE): number {
  return storeClock(date, timeZone).hour < HERO_SLOT_HOURS ? HERO_SLOT_HOURS : 0;
}

export function momentForTime(date: Date = new Date(), timeZone: string = STORE_TIME_ZONE): Moment {
  const { hour } = storeClock(date, timeZone);
  if (hour >= 5 && hour < 12) return 'manana';
  if (hour >= 12 && hour < 19) return 'tarde';
  return 'noche';
}

/** `/?hero=c` previews an enabled variant without waiting for its slot. */
export function parseHeroVariant(value: unknown): HeroVariant | null {
  const raw = Array.isArray(value) ? value[0] : value;
  return typeof raw === 'string' && (HERO_VARIANTS as readonly string[]).includes(raw.toLowerCase())
    ? raw.toLowerCase() as HeroVariant
    : null;
}

// Content shapes, identical to the API's (optional fields may arrive missing or null).
export type HeroTile = { label: string; title: string; imageUrl: string; alt?: string | null; categorySlug: string };
export type HeroAContent = {
  meta?: string | null; title: string; italic?: string | null; body?: string | null; ctaLabel: string;
  main: HeroTile; tiles: HeroTile[];
};
export type HeroBContent = { title?: string | null; italic?: string | null; body?: string | null };
export type HeroCampaignContent = {
  world: string; eyebrow?: string | null; title: string; italic?: string | null; body?: string | null; productSlug: string;
};
export type HeroCContent = { campaigns: HeroCampaignContent[] };
export type HeroMomentContent = {
  id: Moment; label: string; line: string; imageUrl: string; alt?: string | null; productSlugs: string[];
};
export type HeroDContent = { title: string; italic?: string | null; moments: HeroMomentContent[] };
export type HeroContents = { a: HeroAContent; b: HeroBContent; c: HeroCContent; d: HeroDContent };

/** Mirrors the V19 seed; shown when the API is unreachable or returns nothing usable. */
export const DEFAULT_HEROES: HeroContents = {
  a: {
    meta: 'Temporada / 01',
    title: 'Cosas bien elegidas,', italic: 'para cada parte de tu día.',
    body: 'Lo que escuchas, lo que habitas y lo que llevas contigo. Una selección con el mismo criterio.',
    ctaLabel: 'Explorar la tienda',
    main: { label: '01 / Hogar', title: 'El arte de habitar.', imageUrl: '/products/hero.jpg',
      alt: 'Sala cálida con piezas de diseño, materiales naturales y luz de la tarde', categorySlug: 'living' },
    tiles: [
      { label: '02 / Audio', title: 'El sonido de tu espacio.', imageUrl: '/products/headphones.jpg', alt: 'Auriculares Halo', categorySlug: 'audio' },
      { label: '03 / Accesorios', title: 'Detalles que te acompañan.', imageUrl: '/products/watch.jpg', alt: 'Reloj Línea', categorySlug: 'accesorios' },
    ],
  },
  b: { title: null, italic: 'Un mismo criterio.', body: 'Elegimos cada pieza con la misma pregunta: ¿se va a quedar contigo?' },
  c: {
    campaigns: [
      { world: 'Audio', eyebrow: 'Nuevo en Audio', title: 'El ritual', italic: 'de escuchar.',
        body: 'Una pieza que devuelve intención a tus discos y calidez a tu espacio.', productSlug: 'tempo' },
      { world: 'Hogar', eyebrow: 'Destacado en Hogar', title: 'La luz', italic: 'cambia todo.',
        body: 'Una lámpara de mesa para crear rincones cálidos al final del día.', productSlug: 'luna' },
      { world: 'Accesorios', eyebrow: 'Esenciales en Accesorios', title: 'Precisión', italic: 'para todos tus días.',
        body: 'Precisión de líneas y equilibrio de proporciones. Un reloj para acompañarte siempre.', productSlug: 'linea' },
    ],
  },
  d: {
    title: 'Tu día,', italic: 'mejor elegido.',
    moments: [
      { id: 'manana', label: 'Mañana', line: 'Para salir con todo.', imageUrl: '/products/bag.jpg', alt: 'Bolsa Folio',
        productSlugs: ['flow', 'aero', 'sol'] },
      { id: 'tarde', label: 'Tarde', line: 'Para tu espacio.', imageUrl: '/products/hero.jpg', alt: 'Sala cálida con luz de la tarde',
        productSlugs: ['arc', 'hora', 'onda'] },
      { id: 'noche', label: 'Noche', line: 'Para bajar el ritmo.', imageUrl: '/products/editorial.jpg', alt: 'Interior sereno con luz suave',
        productSlugs: ['luna', 'tempo', 'halo'] },
    ],
  },
};

export const EDITORIAL = {
  eyebrow: 'Perspectivas / 02 — Audio',
  title: 'El ritual', titleEnd: 'de', italic: 'escuchar.',
  body: 'Una pieza que devuelve intención a tus discos y calidez a tu espacio.',
  cta: 'Descubrir Audio', categorySlug: 'audio',
};

export const GIFT_BANDS = [
  { id: 'low', label: 'Menos de $1,000', min: undefined, max: '999.99' },
  { id: 'mid', label: '$1,000 – $2,000', min: '1000', max: '2000' },
  { id: 'high', label: 'Más de $2,000', min: '2000.01', max: undefined },
] as const;
