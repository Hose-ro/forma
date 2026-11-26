'use client';

import Link from 'next/link';
import Script from 'next/script';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { z } from 'zod';
import { api } from '@/lib/api/client';
import { marketing } from '@/lib/marketing/tracking';
import { ApiError, errorMessage } from '@/lib/api/http';
import { googleChallengeSchema, googleLoginSchema, googleRegistrationSchema, userSchema } from '@/lib/api/schemas';
import { useShop } from '@/stores/shop';
import type { User } from '@/types';
import { PasswordField } from './password-field';

type GoogleButtonApi = {
  initialize: (options: {
    client_id: string;
    nonce: string;
    callback: (response: { credential: string }) => void;
  }) => void;
  renderButton: (element: HTMLElement, options: {
    theme: 'outline';
    size: 'large';
    text: 'continue_with';
    locale: 'es-419';
  }) => void;
};

function GoogleButton({ onCredential }: { onCredential: (credential: string) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const callback = useRef(onCredential);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => { callback.current = onCredential; }, [onCredential]);
  useEffect(() => {
    if (!ready || !container.current) return;
    const node = container.current;
    const google = (window as Window & { google?: { accounts: { id: GoogleButtonApi } } }).google;
    let active = true;
    async function renderButton() {
      try {
        const { clientId, nonce } = await api.post('/auth/google/challenge', googleChallengeSchema);
        if (!active) return;
        if (!google) throw new Error('Google Identity Services did not load');
        google.accounts.id.initialize({
          client_id: clientId,
          nonce,
          callback: ({ credential }) => { if (active && credential) callback.current(credential); },
        });
        google.accounts.id.renderButton(node, { theme: 'outline', size: 'large', text: 'continue_with', locale: 'es-419' });
      } catch {
        if (active) setFailed(true);
      }
    }
    void renderButton();
    return () => {
      active = false;
      node.replaceChildren();
    };
  }, [ready, attempt]);

  return <>
    <Script src="https://accounts.google.com/gsi/client?hl=es-419" onReady={() => setReady(true)}
      onError={() => setFailed(true)} />
    <div ref={container} />
    {failed && <>
      <p className="error" role="alert">El acceso con Google no está disponible en este momento.</p>
      {ready && <button className="text-link" type="button" onClick={() => {
        setFailed(false);
        setAttempt(value => value + 1);
      }}>Reintentar con Google</button>}
    </>}
  </>;
}

function googleError(error: unknown, linking = false) {
  if (error instanceof ApiError) {
    if (error.status === 409) return linking
      ? 'Esta cuenta de Google ya está vinculada.'
      : 'Si ya tienes una cuenta en Olvenio, inicia sesión con tu contraseña y vincula Google desde Tu perfil.';
    if (error.status === 410) return 'La confirmación venció. Vuelve a elegir tu cuenta de Google.';
    if (error.status === 401) return linking
      ? 'No pudimos confirmar la vinculación. Revisa tu contraseña de Olvenio o vuelve a elegir tu cuenta de Google.'
      : 'No pudimos confirmar tu identidad con Google. Inténtalo de nuevo.';
    if (error.status === 422) {
      if (!linking) return 'Para usar este correo, crea y confirma tu cuenta de Olvenio primero. Después vincula Google desde Tu perfil.';
      if (error.detail === 'Confirm your Olvenio email before linking Google') {
        return 'Confirma el correo de tu cuenta de Olvenio antes de vincular Google.';
      }
      return 'El correo de Google debe coincidir con el de tu cuenta de Olvenio.';
    }
  }
  return errorMessage(error, 'No pudimos continuar con Google. Inténtalo de nuevo.');
}

export function GoogleAccountAccess({ onSignedIn, onBusyChange }: {
  onSignedIn: () => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const syncAfterAuth = useShop(state => state.syncAfterAuth);
  const pending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [registration, setRegistration] = useState<z.infer<typeof googleRegistrationSchema> | null>(null);

  async function run(action: () => Promise<User | undefined>) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    onBusyChange(true);
    setError('');
    try {
      const user = await action();
      if (user) {
        await syncAfterAuth(user);
        onSignedIn();
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 410) {
        setRegistration(null);
        setAttempt(value => value + 1);
      }
      setError(googleError(err));
    } finally {
      pending.current = false;
      setBusy(false);
      onBusyChange(false);
    }
  }

  function signIn(credential: string) {
    void run(async () => {
      const result = await api.post('/auth/google/login', googleLoginSchema, { credential });
      if (result.user) return result.user;
      setRegistration(result.registration);
    });
  }

  function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!registration) return;
    const fields = new FormData(event.currentTarget);
    void run(async () => {
      const { data: user, eventId } = await api.postWithMarketingReceipt('/auth/google/register', userSchema, {
        ticket: registration.ticket,
        firstName: String(fields.get('firstName')).trim(),
        lastName: String(fields.get('lastName')).trim(),
        termsAccepted: fields.has('termsAccepted'),
      });
      marketing.register(eventId);
      return user;
    });
  }

  return <div className="account-google" aria-busy={busy}>
    {registration ? <form className="account-form" onSubmit={register}>
      <h2>Completa tu registro</h2>
      <p className="muted">{registration.email}</p>
      <div className="account-name-fields">
        <label>Nombre<input name="firstName" defaultValue={registration.firstName} required maxLength={100} autoComplete="given-name" /></label>
        <label>Apellidos<input name="lastName" defaultValue={registration.lastName} required maxLength={100} autoComplete="family-name" /></label>
      </div>
      <div className="account-terms">
        <label><input name="termsAccepted" type="checkbox" required />Acepto los términos y condiciones.</label>
        <Link href="/policies/terms" target="_blank" rel="noopener noreferrer">Leer los términos y condiciones</Link>
      </div>
      <button className="button" disabled={busy}>{busy ? 'Creando cuenta…' : 'Crear cuenta con Google'}</button>
      <button className="text-link" type="button" disabled={busy} onClick={() => {
        setRegistration(null);
        setError('');
      }}>Cancelar</button>
    </form> : <div hidden={busy}><GoogleButton key={attempt} onCredential={signIn} /></div>}
    {busy && !registration && <p role="status">Conectando con Google…</p>}
    {error && <p className="error" role="alert">{error}</p>}
  </div>;
}

export function GoogleAccountLink({ user }: { user: User }) {
  const [open, setOpen] = useState(false);
  const [credential, setCredential] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function link(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const form = event.currentTarget;
    const password = String(new FormData(form).get('password') ?? '');
    setError('');
    setBusy(true);
    try {
      const account = await api.post('/auth/google/link', userSchema, { credential, password });
      useShop.setState(state => state.user?.id === account.id ? { user: account } : state);
      setCredential('');
      setOpen(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 410) setCredential('');
      setError(googleError(err, true));
    } finally {
      form.reset();
      setBusy(false);
    }
  }

  function reset() {
    setCredential('');
    setError('');
    setOpen(false);
  }

  if (user.googleLinked) return <p className="account-google" role="status">Cuenta de Google vinculada</p>;
  return <div className="account-google">
    {open ? <>
      {credential ? <form className="account-form" onSubmit={link} aria-busy={busy}>
        <PasswordField label="Confirma tu contraseña de Olvenio" name="password" />
        <button className="button" disabled={busy}>{busy ? 'Vinculando…' : 'Confirmar y vincular'}</button>
      </form> : <>
        <p className="muted">Elige la cuenta de Google con el mismo correo que usas en Olvenio.</p>
        <GoogleButton onCredential={value => { setError(''); setCredential(value); }} />
      </>}
      {error && <p className="error" role="alert">{error}</p>}
      <button className="text-link" type="button" disabled={busy} onClick={reset}>Cancelar vinculación</button>
    </> : <button className="text-link" type="button" onClick={() => setOpen(true)}>Vincular cuenta de Google</button>}
  </div>;
}
