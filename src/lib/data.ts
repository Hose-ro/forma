import type { Supplier } from '@/types';

export const FALLBACK_IMAGE = '/products/hero.jpg';
/** Local photo library offered by the admin product editor. */
export const PHOTO_LIBRARY = ['headphones', 'speaker', 'earbuds', 'turntable', 'chair', 'lamp', 'vase', 'clock', 'bag', 'watch', 'sunglasses', 'bottle'];
/** Illustrative directory only (explicitly labelled as fictitious in the admin). */
export const suppliers: Supplier[] = [{id:'s1',name:'Estudio Sonoro (demo)',location:'Ciudad de México',category:'Audio',leadTime:'5–8 días estimados'},{id:'s2',name:'Taller Materia (demo)',location:'Guadalajara',category:'Living',leadTime:'7–12 días estimados'},{id:'s3',name:'Objetos del Sur (demo)',location:'Puebla',category:'Accesorios',leadTime:'4–7 días estimados'}];
export const money = (amount:number) => new Intl.NumberFormat('es-MX',{style:'currency',currency:'MXN',maximumFractionDigits:0}).format(amount);
export const ORDER_STATUS: Record<string, string> = { DRAFT: 'Cotización', PENDING_PAYMENT: 'Pendiente de pago', CANCELLED: 'Cancelado', PAID: 'Pagado', PAYMENT_REVIEW: 'En revisión', REFUNDED: 'Reembolsado', PARTIALLY_REFUNDED: 'Reembolso parcial' };
export const FULFILLMENT_STATUS: Record<string, string> = { UNFULFILLED: 'Por preparar', PREPARING: 'En preparación', SHIPPED: 'Enviado', DELIVERED: 'Entregado' };
export const formatDate = (iso: string) => new Date(iso).toLocaleDateString('es-MX');
