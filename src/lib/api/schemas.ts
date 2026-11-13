import { z } from 'zod';

// Runtime contracts for the Spring Boot API (null fields are omitted by the backend).
const page = <T extends z.ZodType>(item: T) =>
  z.object({ items: z.array(item), page: z.number(), size: z.number(), totalElements: z.number(), totalPages: z.number() });

export const categorySchema = z.object({
  id: z.string(), slug: z.string(), name: z.string(), description: z.string().nullish(),
  imageUrl: z.string().nullish(), sortOrder: z.number(),
});
export const categoriesSchema = z.array(categorySchema);

const categoryRef = z.object({ slug: z.string(), name: z.string() });
const productBase = {
  id: z.string(), slug: z.string(), name: z.string(), category: categoryRef, price: z.number(),
  compareAtPrice: z.number().nullish(), currency: z.string(), featured: z.boolean(), available: z.boolean(),
  createdAt: z.string(),
};
export const productSummarySchema = z.object({ ...productBase, imageUrl: z.string().nullish(), defaultVariantId: z.string().nullish() });
export const productPageSchema = page(productSummarySchema);
export const productDetailSchema = z.object({
  ...productBase, description: z.string().nullish(), material: z.string().nullish(),
  images: z.array(z.object({ url: z.string(), altText: z.string().nullish() })),
  variants: z.array(z.object({
    id: z.string(), sku: z.string(), name: z.string(), color: z.string().nullish(), price: z.number(),
    compareAtPrice: z.number().nullish(), currency: z.string(), stock: z.number(), available: z.boolean(),
    imageUrl: z.string().nullish(),
  })),
});

export const cartSchema = z.object({
  id: z.string(), currency: z.string(), itemCount: z.number(), subtotal: z.number(), checkoutReady: z.boolean(),
  items: z.array(z.object({
    id: z.string(), variantId: z.string(), productId: z.string(), productSlug: z.string(), productName: z.string(),
    variantName: z.string(), sku: z.string(), imageUrl: z.string().nullish(), unitPrice: z.number(),
    quantity: z.number(), lineTotal: z.number(), stock: z.number(), available: z.boolean(),
  })),
});

export const userSchema = z.object({
  id: z.string(), email: z.string(), firstName: z.string(), lastName: z.string(), phone: z.string().nullish(),
  role: z.enum(['CUSTOMER', 'ADMIN']), createdAt: z.string(),
});

const orderStatus = z.enum(['DRAFT', 'PENDING_PAYMENT', 'PAID', 'PAYMENT_REVIEW', 'REFUNDED', 'PARTIALLY_REFUNDED', 'CANCELLED']);
export const orderSchema = z.object({
  id: z.string(), orderNumber: z.string().nullish(), status: orderStatus, paymentStatus: z.string(),
  customer: z.object({ email: z.string(), firstName: z.string(), lastName: z.string(), phone: z.string().nullish() }),
  shippingAddress: z.object({
    recipientName: z.string(), line1: z.string(), line2: z.string().nullish(), city: z.string(), state: z.string(),
    postalCode: z.string(), country: z.string(), phone: z.string().nullish(),
  }).nullish(),
  currency: z.string(),
  items: z.array(z.object({
    productId: z.string().nullish(), variantId: z.string().nullish(), sku: z.string(), productName: z.string(),
    productSlug: z.string(), variantName: z.string(), imageUrl: z.string().nullish(), unitPrice: z.number(),
    quantity: z.number(), lineTotal: z.number(),
  })),
  subtotal: z.number(), shippingTotal: z.number(), total: z.number(), quoteExpiresAt: z.string(),
  placedAt: z.string().nullish(), cancelledAt: z.string().nullish(), createdAt: z.string(),
  fulfillmentStatus: z.string().nullish(), carrier: z.string().nullish(), trackingNumber: z.string().nullish(), shippedAt: z.string().nullish(),
});
export const orderSummarySchema = z.object({
  id: z.string(), orderNumber: z.string().nullish(), status: orderStatus, email: z.string(), customerName: z.string(),
  currency: z.string(), total: z.number(), itemCount: z.number(), placedAt: z.string().nullish(), createdAt: z.string(), fulfillmentStatus: z.string().nullish(),
});
export const orderPageSchema = page(orderSummarySchema);

export const adminProductSchema = z.object({
  id: z.string(), slug: z.string(), name: z.string(), description: z.string().nullish(), material: z.string().nullish(),
  categoryId: z.string(), categorySlug: z.string(), status: z.enum(['DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED']),
  featured: z.boolean(), available: z.boolean(), price: z.number(), compareAtPrice: z.number().nullish(),
  currency: z.string(), images: z.array(z.object({ url: z.string(), altText: z.string().nullish() })),
  variants: z.array(z.object({
    id: z.string(), sku: z.string(), name: z.string(), color: z.string().nullish(), price: z.number(),
    compareAtPrice: z.number().nullish(), currency: z.string(), stock: z.number(), active: z.boolean(),
    position: z.number(), imageUrl: z.string().nullish(),
  })),
  createdAt: z.string(), updatedAt: z.string(),
});
export const adminProductPageSchema = page(adminProductSchema);
export const deleteResultSchema = z.object({ id: z.string(), outcome: z.enum(['DELETED', 'ARCHIVED']) });
export const dashboardSchema = z.object({
  totalProducts: z.number(), activeProducts: z.number(), registeredOrders: z.number(), pendingOrders: z.number(),
});
export const csrfSchema = z.object({ headerName: z.string(), token: z.string() });

export const preferenceSchema = z.object({paymentId:z.string(),checkoutUrl:z.url()});
export const paymentSchema = z.object({
  id:z.string(),orderId:z.string(),provider:z.string(),status:z.enum(['CREATED','PENDING','IN_PROCESS','APPROVED','REJECTED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED','UNKNOWN']),
  amount:z.number(),currency:z.string(),providerPaymentId:z.string().nullish(),providerPreferenceId:z.string().nullish(),createdAt:z.string(),approvedAt:z.string().nullish(),failureReason:z.string().nullish(),
});
export const adminPaymentsSchema = z.array(z.object({id:z.string(),order_id:z.string(),order_number:z.string(),provider:z.string(),status:z.string(),amount:z.number(),currency:z.string(),created_at:z.string(),provider_payment_id:z.string().nullish()}));
export const adminPaymentSchema = z.object({payment:paymentSchema,events:z.array(z.object({event_type:z.string(),processing_status:z.string(),received_at:z.string(),payload:z.string()}))});
