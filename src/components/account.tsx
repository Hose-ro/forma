'use client';

import { useEffect, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { ArrowUpRight, CheckCircle2, Mail, Package } from 'lucide-react';
import { api } from '@/lib/api/client';
import { ApiError, errorMessage } from '@/lib/api/http';
import { orderPageSchema } from '@/lib/api/schemas';
import { LIVE } from '@/lib/business';
import { ORDER_STATUS, formatDate, money } from '@/lib/data';
import { safeRedirect } from '@/lib/safe-redirect';
import { useShop } from '@/stores/shop';
import type { OrderSummary, User } from '@/types';
import { PasswordField } from './password-field';

type Mode = 'login' | 'register' | 'forgot';

export function Account() {
  const { user, sessionReady } = useShop();
  if (!sessionReady) return <div className="page skeleton" aria-label="Cargando tu cuenta" />;

  if (user) return <div className="page account-page account-dashboard">
    <header className="account-heading">
      <h1>Mi cuenta</h1>
      <p>Hola, {user.firstName}.</p>
    </header>
    <div className="account-dashboard-grid">
      <Orders key={user.id} signedIn />
      <aside className="account-sidebar">
        <Profile key={user.id} user={user} />
      </aside>
    </div>
  </div>;

  return <div className="page account-page">
    <div className="page-intro">
      <h1>Tu cuenta <i>Olvenio.</i></h1>
      <p>También puedes comprar como invitado.</p>
    </div>
    <div className="account-layout">
      <AccountForm />
      <Orders signedIn={false} />
    </div>
  </div>;
}

function AccountForm() {
  const { login, register } = useShop();
  const router = useRouter();
  const next = useSearchParams().get('next');
  const [mode, setMode] = useState<Mode>('login');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const creating = mode === 'register';

  function changeMode(value: Mode) {
    setMode(value);
    setError('');
    setNotice('');
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    const email = String(fields.get('email')).trim();
    const password = String(fields.get('password') ?? '');
    const passwordConfirmation = String(fields.get('passwordConfirmation') ?? '');
    const termsAccepted = fields.has('termsAccepted');
    setError('');
    setNotice('');
    if (creating && password !== passwordConfirmation) {
      setError('Las contraseñas no coinciden.');
      event.currentTarget.querySelector<HTMLInputElement>('[name="passwordConfirmation"]')?.focus();
      return;
    }
    if (creating && !termsAccepted) {
      setError('Acepta los términos y condiciones para continuar.');
      return;
    }
    if (new TextEncoder().encode(password).length > 72) {
      setError('La contraseña es demasiado larga. Algunos caracteres, como los acentos, ocupan más espacio.');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'forgot') {
        await api.post('/auth/password/forgot', null, { email });
        setNotice('Si existe una cuenta con ese correo, recibirás un enlace para restablecer la contraseña. Vence en 30 minutos.');
        return;
      }
      if (mode === 'login') await login(email, password);
      else await register({
        firstName: String(fields.get('name')).trim(), lastName: String(fields.get('lastName')).trim(),
        email, password, passwordConfirmation, termsAccepted,
      });
      const destination = safeRedirect(next ?? '');
      if (destination) router.replace(destination);
      router.refresh();
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) setError('Correo o contraseña incorrectos.');
      else if (err instanceof ApiError && err.status === 409) setError('Ya existe una cuenta con ese correo. Inicia sesión.');
      else setError(errorMessage(err, 'No pudimos completar la operación. Inténtalo de nuevo.'));
    } finally {
      setBusy(false);
    }
  }

  return <section aria-label={creating ? 'Crear cuenta' : 'Acceso a tu cuenta'}>
    <div className="category-tabs">
      <button type="button" className={!creating ? 'active' : ''} aria-pressed={!creating} disabled={busy}
        onClick={() => changeMode('login')}>Iniciar sesión</button>
      <button type="button" className={creating ? 'active' : ''} aria-pressed={creating} disabled={busy}
        onClick={() => changeMode('register')}>Crear cuenta</button>
    </div>
    <form className="account-form" onSubmit={submit}>
      {creating && <div className="account-name-fields">
        <label>Nombre<input name="name" required maxLength={100} autoComplete="given-name" /></label>
        <label>Apellidos<input name="lastName" required maxLength={100} autoComplete="family-name" /></label>
      </div>}
      <label>Correo electrónico<input name="email" type="email" required maxLength={320} autoComplete="email" /></label>
      {mode !== 'forgot' && <PasswordField key={mode} label="Contraseña" name="password" creating={creating} />}
      {creating && <>
        <PasswordField label="Repite la contraseña" name="passwordConfirmation" creating />
        <p className="muted">Usa al menos 10 caracteres.</p>
        <div className="account-terms">
          <label><input type="checkbox" name="termsAccepted" required aria-describedby="registration-terms" />
            Acepto los términos y condiciones.
          </label>
          <Link id="registration-terms" href="/policies/terms" target="_blank" rel="noopener noreferrer">Leer los términos y condiciones</Link>
        </div>
      </>}
      {mode === 'forgot' && <p className="muted">Escribe el correo de tu cuenta y te enviaremos un enlace de un solo uso.</p>}
      {notice && <p className="success-message" role="status">{notice}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <button className="button" disabled={busy}>
        {busy ? 'Un momento…' : mode === 'login' ? 'Entrar' : mode === 'forgot' ? 'Enviar enlace' : 'Crear cuenta'}
        <ArrowUpRight size={17} aria-hidden="true" />
      </button>
      {!creating && <button type="button" className="text-link" disabled={busy}
        onClick={() => changeMode(mode === 'login' ? 'forgot' : 'login')}>
        {mode === 'login' ? '¿Olvidaste tu contraseña?' : 'Volver a iniciar sesión'}
      </button>}
    </form>
  </section>;
}

function Orders({ signedIn }: { signedIn: boolean }) {
  const [page, setPage] = useState(0);
  const [pages, setPages] = useState(1);
  const [orders, setOrders] = useState<OrderSummary[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    api.get(`${signedIn ? '/me/orders' : '/orders'}?size=10&page=${page}`, orderPageSchema).then(result => {
      if (!active) return;
      setOrders(result.items);
      setPages(Math.max(1, result.totalPages));
    }, err => {
      if (active) setError(errorMessage(err, 'No pudimos cargar tus pedidos.'));
    });
    return () => { active = false; };
  }, [signedIn, page]);

  function showPage(value: number) {
    setOrders(null);
    setError('');
    setPage(value);
  }

  return <section className="account-orders" aria-labelledby="account-orders-title">
    <header className="account-section-heading">
      <h2 id="account-orders-title">{signedIn ? 'Tus pedidos' : 'Pedidos de este navegador'}</h2>
      <p>{signedIn ? 'Consulta el estado y los detalles de tus compras.' : 'Pedidos realizados como invitado desde este navegador.'}</p>
    </header>
    {error ? <p className="error" role="alert">{error}</p>
      : orders === null ? <div className="skeleton" role="status" aria-label="Cargando pedidos" />
      : orders.length ? <ul className="account-order-list">{orders.map(order => <li key={order.id}>
        <Link className="account-order" href={`/account/orders/${order.id}`}>
          <div>
            <strong>{order.orderNumber || 'Detalle del pedido'}</strong>
            <p>{formatDate(order.placedAt || order.createdAt)} · {ORDER_STATUS[order.status]}</p>
            {!LIVE && <small>Sin cobro · Demostración</small>}
          </div>
          <span>{money(order.total)} <ArrowUpRight size={17} aria-hidden="true" /></span>
        </Link>
      </li>)}</ul>
      : <div className="account-orders-empty">
        <span className="account-empty-icon"><Package size={26} strokeWidth={1.5} aria-hidden="true" /></span>
        <h3>{page > 0 ? 'No hay pedidos en esta página' : 'Aún no tienes pedidos'}</h3>
        {page === 0 && <Link className="text-link" href="/shop">Explorar la colección <ArrowUpRight size={17} aria-hidden="true" /></Link>}
      </div>}
    {pages > 1 && <nav className="pager" aria-label="Páginas de pedidos">
      <span>Página {page + 1} de {pages}</span>
      <div>
        <button disabled={page === 0 || (orders === null && !error)} onClick={() => showPage(page - 1)}>Anteriores</button>
        <button disabled={page + 1 >= pages || (orders === null && !error)} onClick={() => showPage(page + 1)}>Siguientes</button>
      </div>
    </nav>}
  </section>;
}

function Profile({ user }: { user: User }) {
  const router = useRouter();
  const { logout } = useShop();
  const [pending, setPending] = useState<'resend' | 'logout' | null>(null);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');

  async function act(action: 'resend' | 'logout') {
    setPending(action);
    setNotice('');
    setError('');
    try {
      if (action === 'resend') {
        await api.post('/auth/email/verification', null);
        setNotice('Te enviamos un enlace de confirmación.');
      } else {
        await logout();
        router.refresh();
      }
    } catch (err) {
      setError(errorMessage(err, 'No pudimos completar la solicitud.'));
    } finally {
      setPending(null);
    }
  }

  return <section className="account-profile" aria-labelledby="account-profile-title">
    <h2 id="account-profile-title">Tu perfil</h2>
    <div className="account-identity">
      <span className="account-initials" aria-hidden="true">{user.firstName.slice(0, 1)}{user.lastName.slice(0, 1)}</span>
      <div><strong>{user.firstName} {user.lastName}</strong><p>{user.email}</p></div>
    </div>
    {user.emailVerified === false && <div className="account-email">
      <p><Mail size={16} aria-hidden="true" /> Correo pendiente de confirmar</p>
      <button type="button" className="text-link" disabled={!!pending} onClick={() => void act('resend')}>
        {pending === 'resend' ? 'Solicitando…' : 'Reenviar enlace'}
      </button>
    </div>}
    {user.emailVerified && <p className="account-verified"><CheckCircle2 size={16} aria-hidden="true" /> Correo confirmado</p>}
    {notice && <p className="success-message" role="status">{notice}</p>}
    {error && <p className="error" role="alert">{error}</p>}
    <div className="account-profile-actions">
      {user.role === 'ADMIN' && <Link className="text-link" href="/admin">Administración <ArrowUpRight size={15} aria-hidden="true" /></Link>}
      <button type="button" className="text-link" disabled={!!pending} onClick={() => void act('logout')}>
        {pending === 'logout' ? 'Cerrando…' : 'Cerrar sesión'}
      </button>
    </div>
  </section>;
}
