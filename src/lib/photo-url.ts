const IMAGE_HOSTS = (process.env.NEXT_PUBLIC_IMAGE_HOSTS || '').split(',').filter(Boolean);
export { IMAGE_HOSTS };

/** Same rule next/image enforces on the storefront: site-relative paths or https on an allow-listed host. */
export function photoProblem(url: string) {
  if (/^\/(?!\/)\S*$/.test(url)) return '';
  try {
    const u = new URL(url);
    if (u.protocol !== 'https:') return 'La fotografía debe usar una dirección https://.';
    if (!IMAGE_HOSTS.includes(u.hostname)) return `El dominio ${u.hostname} no está autorizado para imágenes. Agrégalo a IMAGE_ALLOWED_HOSTS en el despliegue de la tienda (hoy: ${IMAGE_HOSTS.join(', ') || 'ninguno'}).`;
    return '';
  } catch { return 'Escribe una dirección completa (https://…) o una ruta del sitio (/products/archivo.jpg).'; }
}
