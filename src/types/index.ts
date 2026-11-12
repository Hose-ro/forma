import type { z } from 'zod';
import type { adminProductSchema, cartSchema, categorySchema, dashboardSchema, orderSchema, orderSummarySchema, userSchema } from '@/lib/api/schemas';

export type ProductVariant = { id: string; sku: string; name: string; color: string; price: number; stock: number; image: string; available: boolean };
/** Storefront view model. List results carry no variants, only `defaultVariantId` for quick add. */
export type Product = { id: string; slug: string; name: string; description: string; category: string; categorySlug: string; price: number; compareAtPrice?: number; currency: string; images: string[]; variants: ProductVariant[]; defaultVariantId: string | null; available: boolean; featured: boolean; createdAt: string; material: string };
export type ProductPage = { items: Product[]; page: number; totalElements: number; totalPages: number };
export type Category = z.infer<typeof categorySchema>;
export type Cart = z.infer<typeof cartSchema>;
export type CartLine = Cart['items'][number];
export type User = z.infer<typeof userSchema>;
export type Order = z.infer<typeof orderSchema>;
export type OrderSummary = z.infer<typeof orderSummarySchema>;
export type AdminProduct = z.infer<typeof adminProductSchema>;
export type Dashboard = z.infer<typeof dashboardSchema>;
export type Supplier = { id: string; name: string; location: string; category: string; leadTime: string };
