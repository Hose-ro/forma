'use client';

import { Moon, Sun } from 'lucide-react';
import { useEffect, useSyncExternalStore } from 'react';
import { DARK_MODE_QUERY, THEME_COLORS, THEME_META_ID, THEME_STORAGE_KEY } from '@/lib/theme';

const THEME_CHANGE = 'olvenio-theme-change';

function isDark() {
  const theme = document.documentElement.dataset.theme;
  return theme === 'dark' || (!theme && window.matchMedia(DARK_MODE_QUERY).matches);
}

function subscribe(onChange: () => void) {
  const systemTheme = window.matchMedia(DARK_MODE_QUERY);
  systemTheme.addEventListener('change', onChange);
  window.addEventListener(THEME_CHANGE, onChange);
  return () => {
    systemTheme.removeEventListener('change', onChange);
    window.removeEventListener(THEME_CHANGE, onChange);
  };
}

function toggleTheme() {
  const theme = isDark() ? 'light' : 'dark';
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // The current page can still switch themes if storage is unavailable.
  }
  window.dispatchEvent(new Event(THEME_CHANGE));
}

export function ThemeToggle({ placement = 'header' }: { placement?: 'header' | 'menu' | 'admin' }) {
  const dark = useSyncExternalStore(subscribe, isDark, () => false);

  useEffect(() => {
    document.getElementById(THEME_META_ID)?.setAttribute('content', THEME_COLORS[dark ? 'dark' : 'light']);
  }, [dark]);

  return (
    <button
      className={`theme-toggle theme-toggle--${placement}`}
      type="button"
      aria-label="Modo oscuro"
      aria-pressed={dark}
      title="Modo oscuro"
      onClick={toggleTheme}
    >
      <span className="theme-toggle__icons" aria-hidden="true">
        <Moon className="theme-toggle__moon" size={18} />
        <Sun className="theme-toggle__sun" size={18} />
      </span>
      {placement === 'menu' && <span>Modo oscuro</span>}
    </button>
  );
}
