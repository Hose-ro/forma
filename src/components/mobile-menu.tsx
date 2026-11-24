'use client';

import * as Dialog from '@radix-ui/react-dialog';
import { ArrowUpRight, UserRound, X } from 'lucide-react';
import Link from 'next/link';
import { useEffect } from 'react';
import { ThemeToggle } from './theme-toggle';
import styles from './mobile-menu.module.css';

const categories = ['Audio', 'Living', 'Accesorios'];

export function MobileMenu({ open, onOpenChange }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  useEffect(() => {
    if (!open) return;

    const viewport = window.matchMedia('(max-width: 800px)');
    const closeOnDesktop = () => {
      if (!viewport.matches) onOpenChange(false);
    };
    closeOnDesktop();
    viewport.addEventListener('change', closeOnDesktop);
    return () => viewport.removeEventListener('change', closeOnDesktop);
  }, [open, onOpenChange]);

  const close = () => onOpenChange(false);

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Trigger type="button" className="icon-button mobile-menu" aria-label="Abrir menú">
        <span className={styles.menuIcon} aria-hidden="true"><span /><span /></span>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.panel} aria-describedby={undefined}>
          <div className={styles.heading}>
            <Dialog.Title className="sr-only">Menú de Olvenio</Dialog.Title>
            <span className="logo" aria-hidden="true">OLVENIO<span className={styles.dot}>.</span></span>
            <Dialog.Close type="button" className={`icon-button ${styles.close}`} aria-label="Cerrar menú">
              <span><X size={20} aria-hidden="true" /></span>
            </Dialog.Close>
          </div>

          <nav className={styles.navigation} aria-label="Navegación móvil">
            <Link href="/shop" className={styles.feature} onNavigate={close}>
              <span>Colección</span>
              <span className={styles.arrow}><ArrowUpRight size={20} aria-hidden="true" /></span>
            </Link>
            <ul className={styles.categories}>
              {categories.map(category => (
                <li key={category}>
                  <Link href={`/shop?category=${category}`} className={styles.category} onNavigate={close}>
                    {category}
                    <span className={styles.arrow}><ArrowUpRight size={20} aria-hidden="true" /></span>
                  </Link>
                </li>
              ))}
            </ul>
          </nav>

          <div className={styles.utilities}>
            <Link href="/account" className={styles.account} onNavigate={close}>
              <UserRound size={18} aria-hidden="true" />
              Mi cuenta
            </Link>
            <ThemeToggle placement="menu" />
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
