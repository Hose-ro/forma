'use client';
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowUpRight } from 'lucide-react';
import { api } from '@/lib/api/client';
import { PasswordField } from './password-field';
import { ApiError, errorMessage } from '@/lib/api/http';

// Landing pages for the single-use links sent by email. The token travels in the URL fragment (#token=…), which
// browsers never send to a server, and is removed from the address bar as soon as it is read.
const failure=(e:unknown)=>e instanceof ApiError&&e.status===422?'El enlace no es válido o ya venció. Solicita uno nuevo.':e instanceof ApiError&&e.status===400?'La contraseña debe tener de 10 a 72 caracteres.':errorMessage(e,'No pudimos completar la operación. Inténtalo de nuevo.');

export function AccountToken({mode}:{mode:'reset'|'verify'}){
  const [token,setToken]=useState<string|null>(null);
  const [state,setState]=useState<'idle'|'busy'|'done'>('idle');
  const [error,setError]=useState('');
  useEffect(()=>{
    const value=new URLSearchParams(window.location.hash.slice(1)).get('token')||'';
    window.history.replaceState(null,'',window.location.pathname);
    setToken(value);
    if(mode==='verify'&&value){setState('busy');api.post('/auth/email/verify',null,{token:value}).then(()=>setState('done'),e=>{setError(failure(e));setState('idle')})}
  },[mode]);
  async function submit(e:React.FormEvent<HTMLFormElement>){
    e.preventDefault();const fd=new FormData(e.currentTarget);const password=String(fd.get('password'));
    if(password!==String(fd.get('confirm'))){setError('Las contraseñas no coinciden.');return}
    setError('');setState('busy');
    try{await api.post('/auth/password/reset',null,{token,password});setState('done')}catch(err){setError(failure(err));setState('idle')}
  }
  if(token===null)return <div className="page skeleton"/>;
  const reset=mode==='reset';
  return <div className="page account-page"><div className="page-intro"><p className="eyebrow">Tu espacio Olvenio</p><h1>{reset?<>Nueva <i>contraseña.</i></>:<>Confirma tu <i>correo.</i></>}</h1></div><div className="account-layout"><section>
    {state==='done'?<><p className="success-message" role="status">{reset?'Tu contraseña se actualizó y se cerraron las sesiones abiertas. Ya puedes iniciar sesión.':'Tu correo quedó confirmado.'}</p><Link className="text-link" href="/account">Ir a mi cuenta <ArrowUpRight size={17}/></Link></>
    :!token?<><p className="error" role="alert">Este enlace está incompleto. Ábrelo desde el correo que te enviamos o solicita uno nuevo.</p><Link className="text-link" href="/account">Ir a mi cuenta <ArrowUpRight size={17}/></Link></>
    :reset?<form className="account-form" onSubmit={submit}><PasswordField label="Nueva contraseña" name="password" creating/><PasswordField label="Repite la contraseña" name="confirm" creating/><p className="muted">Mínimo 10 caracteres. Al guardarla se cerrarán las sesiones abiertas con la contraseña anterior.</p>{error&&<p className="error" role="alert">{error}</p>}<button className="button" disabled={state==='busy'}>{state==='busy'?'Un momento…':'Guardar contraseña'} <ArrowUpRight size={17}/></button></form>
    :<>{state==='busy'&&<p role="status">Confirmando tu correo…</p>}{error&&<><p className="error" role="alert">{error}</p><Link className="text-link" href="/account">Ir a mi cuenta <ArrowUpRight size={17}/></Link></>}</>}
  </section></div></div>;
}
