import type { z } from 'zod';

/** Error from the API (RFC 9457 problem details) or from the network/contract layer. */
export class ApiError extends Error {
  constructor(
    public status: number,
    public title: string,
    public detail: string,
    public fieldErrors: { field: string; message: string }[] = [],
  ) {
    super(detail || title);
    this.name = 'ApiError';
  }
}

/** User-facing message in Spanish for any thrown value. */
export function errorMessage(e: unknown, fallback = 'Algo no salió como esperábamos. Inténtalo de nuevo.'): string {
  if (e instanceof ApiError) {
    if (e.status === 0) return 'No pudimos conectar con el servidor. Revisa tu conexión e inténtalo de nuevo.';
    if (e.status === 401) return 'Inicia sesión para continuar.';
    if (e.status === 403) return 'No tienes permiso para realizar esta acción.';
    if (e.status >= 500) return 'El servidor no está disponible en este momento.';
    if (e.fieldErrors.length) return 'Revisa los datos del formulario.';
  }
  return fallback;
}

type Options = { method?: string; body?: unknown; headers?: Record<string, string>; credentials?: RequestCredentials; cache?: RequestCache };

/** Performs the request, maps failures to ApiError and validates the JSON body against `schema`. */
export async function request<S extends z.ZodType>(url: string, schema: S | null, opts: Options = {}): Promise<z.infer<S>> {
  let res: Response;
  try {
    res = await fetch(url, {
      method: opts.method || 'GET',
      headers: { Accept: 'application/json', ...(opts.body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...opts.headers },
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      credentials: opts.credentials,
      cache: opts.cache,
    });
  } catch {
    throw new ApiError(0, 'Network error', 'Network error');
  }
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new ApiError(res.status, problem.title || res.statusText, problem.detail || '', Array.isArray(problem.errors) ? problem.errors : []);
  }
  if (!schema || res.status === 204) return undefined as z.infer<S>;
  const parsed = schema.safeParse(await res.json().catch(() => null));
  if (!parsed.success) {
    throw new ApiError(502, 'Unexpected response', 'La respuesta del servidor no tiene el formato esperado.');
  }
  return parsed.data;
}
