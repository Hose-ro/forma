# Funciones compartidas por los hooks. Instálalos con npm run hooks:install.
ROOT="$(git rev-parse --show-toplevel)" || exit 1

require_gitleaks() {
  if ! command -v gitleaks >/dev/null 2>&1; then
    echo "gitleaks no está instalado; instálalo con: brew install gitleaks" >&2
    exit 1
  fi
}

leak_found() {
  echo "" >&2
  echo "gitleaks encontró un posible secreto. Sácalo del código y usa una variable de entorno." >&2
  echo "Si ya salió de tu máquina, rótalo (backend/SECURITY.md → Rotación de secretos)." >&2
  echo "Si es un valor falso de prueba, agrégalo a .gitleaks.toml." >&2
  exit 1
}
