'use client';

import { Eye, EyeOff } from 'lucide-react';
import { useId, useState } from 'react';

export function PasswordField({ label, name, creating = false }: {
  label: string;
  name: string;
  creating?: boolean;
}) {
  const id = useId();
  const [visible, setVisible] = useState(false);

  return <div className="password-field">
    <label htmlFor={id}>{label}</label>
    <div className="password-input">
      <input id={id} name={name} type={visible ? 'text' : 'password'} required
        minLength={creating ? 10 : 1} maxLength={72}
        autoComplete={creating ? 'new-password' : 'current-password'} />
      <button type="button" aria-controls={id} aria-label={`${visible ? 'Ocultar' : 'Mostrar'} ${label.toLowerCase()}`}
        onClick={() => setVisible(!visible)}>
        {visible ? <EyeOff size={19} aria-hidden="true" /> : <Eye size={19} aria-hidden="true" />}
      </button>
    </div>
  </div>;
}
