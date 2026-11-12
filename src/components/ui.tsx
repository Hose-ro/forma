'use client';
import { motion, useReducedMotion } from 'motion/react';
import { Minus, Plus } from 'lucide-react';
import type { ReactNode } from 'react';
export function Reveal({children,className='',delay=0}:{children:ReactNode;className?:string;delay?:number}){const reduced=useReducedMotion();return <motion.div className={className} initial={reduced?false:{opacity:0,y:24}} whileInView={{opacity:1,y:0}} viewport={{once:true,amount:.1}} transition={{duration:.7,delay,ease:[.22,1,.36,1]}}>{children}</motion.div>}
export function Quantity({value,max,onChange}:{value:number;max:number;onChange:(n:number)=>void}){return <div className="quantity"><button type="button" aria-label="Disminuir cantidad" disabled={value<=1} onClick={()=>onChange(value-1)}><Minus size={14}/></button><span aria-live="polite">{value}</span><button type="button" aria-label="Aumentar cantidad" disabled={value>=max} onClick={()=>onChange(value+1)}><Plus size={14}/></button></div>}
export function Empty({title,children}:{title:string;children:ReactNode}){return <div className="empty"><span className="empty-symbol">f.</span><h2>{title}</h2>{children}</div>}
