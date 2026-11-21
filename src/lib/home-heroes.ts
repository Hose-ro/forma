// Home hero rotation and editorial content. Kept free of path aliases so node:test can import it directly.

export const HERO_VARIANTS = ['a', 'b', 'c', 'd'] as const;
export type HeroVariant = (typeof HERO_VARIANTS)[number];

/** Each hero stays up for this many hours, then the next one in HERO_VARIANTS takes over. */
export const HERO_SLOT_HOURS = 12;

/** Slots start at 00:00 and 12:00 store time, so every visitor sees the same hero at the same moment. */
export const STORE_TIME_ZONE = 'America/Mexico_City';

export type Moment = 'manana' | 'tarde' | 'noche';

function storeClock(date: Date, timeZone: string) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23',
  }).formatToParts(date).map(part => [part.type, part.value]));
  const day = Math.floor(Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day)) / 86_400_000);
  return { day, hour: Number(parts.hour) };
}

export function heroForTime(date: Date = new Date(), timeZone: string = STORE_TIME_ZONE): HeroVariant {
  const { day, hour } = storeClock(date, timeZone);
  const slot = day * (24 / HERO_SLOT_HOURS) + Math.floor(hour / HERO_SLOT_HOURS);
  return HERO_VARIANTS[slot % HERO_VARIANTS.length];
}

export function momentForTime(date: Date = new Date(), timeZone: string = STORE_TIME_ZONE): Moment {
  const { hour } = storeClock(date, timeZone);
  if (hour >= 5 && hour < 12) return 'manana';
  if (hour >= 12 && hour < 19) return 'tarde';
  return 'noche';
}

/** `/?hero=c` previews a variant without waiting for its slot. */
export function parseHeroVariant(value: unknown): HeroVariant | null {
  const raw = Array.isArray(value) ? value[0] : value;
  return typeof raw === 'string' && (HERO_VARIANTS as readonly string[]).includes(raw.toLowerCase())
    ? raw.toLowerCase() as HeroVariant
    : null;
}

export type HeroTile = { label: string; title: string; image: string; alt: string; categorySlug: string };

/** Hero A: one large scene plus two tiles for the other worlds. */
export const HERO_A = {
  main: { label: '01 / Hogar', title: 'El arte de habitar.', image: '/products/hero.jpg',
    alt: 'Sala cálida con piezas de diseño, materiales naturales y luz de la tarde', categorySlug: 'living' },
  tiles: [
    { label: '02 / Audio', title: 'El sonido de tu espacio.', image: '/products/headphones.jpg', alt: 'Auriculares Halo', categorySlug: 'audio' },
    { label: '03 / Accesorios', title: 'Detalles que te acompañan.', image: '/products/watch.jpg', alt: 'Reloj Línea', categorySlug: 'accesorios' },
  ],
} satisfies { main: HeroTile; tiles: HeroTile[] };

export type HeroCampaignContent = { id: string; world: string; eyebrow: string; title: string; italic: string; body: string; productSlug: string };

/** Hero C: one product per world; the price and photo come from the live catalog. */
export const HERO_C_CAMPAIGNS: HeroCampaignContent[] = [
  { id: 'audio', world: 'Audio', eyebrow: 'Nuevo en Audio', title: 'El ritual', italic: 'de escuchar.',
    body: 'Una pieza que devuelve intención a tus discos y calidez a tu espacio.', productSlug: 'tempo' },
  { id: 'hogar', world: 'Hogar', eyebrow: 'Destacado en Hogar', title: 'La luz', italic: 'cambia todo.',
    body: 'Una lámpara de mesa para crear rincones cálidos al final del día.', productSlug: 'luna' },
  { id: 'accesorios', world: 'Accesorios', eyebrow: 'Esenciales en Accesorios', title: 'Precisión', italic: 'para todos tus días.',
    body: 'Precisión de líneas y equilibrio de proporciones. Un reloj para acompañarte siempre.', productSlug: 'linea' },
];

export type HeroMomentContent = { id: Moment; label: string; line: string; image: string; alt: string; productSlugs: string[] };

/** Hero D: products from different worlds grouped by the time of day they belong to. */
export const HERO_D_MOMENTS: HeroMomentContent[] = [
  { id: 'manana', label: 'Mañana', line: 'Para salir con todo.', image: '/products/bag.jpg', alt: 'Bolsa Folio',
    productSlugs: ['flow', 'aero', 'sol'] },
  { id: 'tarde', label: 'Tarde', line: 'Para tu espacio.', image: '/products/hero.jpg', alt: 'Sala cálida con luz de la tarde',
    productSlugs: ['arc', 'hora', 'onda'] },
  { id: 'noche', label: 'Noche', line: 'Para bajar el ritmo.', image: '/products/editorial.jpg', alt: 'Interior sereno con luz suave',
    productSlugs: ['luna', 'tempo', 'halo'] },
];

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
