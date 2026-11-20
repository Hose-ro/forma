import { z } from 'zod';

export const returnSchema = z.object({
  kind: z.enum(['RETURN', 'EXCHANGE']), items: z.string(), reason: z.string(),
  status: z.enum(['REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED']), response: z.string().nullish(),
  createdAt: z.string(), updatedAt: z.string(),
});
export const invoiceSchema = z.object({
  taxId: z.string(), legalName: z.string(), postalCode: z.string(), taxRegime: z.string(), cfdiUse: z.string(),
  fiscalId: z.string().nullish(), createdAt: z.string(), issuedAt: z.string().nullish(),
});
export const afterSalesSchema = z.object({
  canReturn: z.boolean(), canInvoice: z.boolean(), returnRequest: returnSchema.nullish(), invoice: invoiceSchema.nullish(),
  trackingUrl: z.url({ protocol: /^https$/ }).nullish(),
});
export const shippingLabelSchema = z.object({
  live: z.boolean(), status: z.enum(['PROCESSING', 'REVIEW', 'READY', 'ABANDONED']), carrier: z.string(), service: z.string(),
  shipmentId: z.string().nullish(), trackingNumber: z.string().nullish(),
  labelUrl: z.url({ protocol: /^https$/ }).nullish(),
});
export const shippingLabelsSchema = z.object({
  enabled: z.boolean(), live: z.boolean(), label: shippingLabelSchema.nullish(),
});
export const shippingQuoteSchema = z.array(z.object({
  carrier: z.string(), service: z.string(), description: z.string(), amount: z.number(), currency: z.literal('MXN'),
}));
export const operationalAlertsSchema = z.array(z.object({
  kind: z.enum(['PAYMENT', 'ORDER_EMAIL', 'ACCOUNT_EMAIL', 'PREPARATION', 'RETURN', 'INVOICE', 'LABEL']),
  orderId: z.string().nullish(), reference: z.string().nullish(), detail: z.string().nullish(),
  since: z.string(), total: z.number(),
}));

export type AfterSales = z.infer<typeof afterSalesSchema>;
export type ReturnRequest = z.infer<typeof returnSchema>;
export type InvoiceRequest = z.infer<typeof invoiceSchema>;
export type ShippingLabels = z.infer<typeof shippingLabelsSchema>;
export type ShippingRate = z.infer<typeof shippingQuoteSchema>[number];
export type OperationalAlert = z.infer<typeof operationalAlertsSchema>[number];

export const RETURN_STATUS: Record<ReturnRequest['status'], string> = {
  REQUESTED: 'En revisión', APPROVED: 'Aprobada', REJECTED: 'No aprobada', COMPLETED: 'Concluida',
};
