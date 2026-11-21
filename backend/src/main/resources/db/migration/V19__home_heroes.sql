-- Home page heroes edited from the admin. One row per variant; the storefront rotates the enabled ones
-- every 12 hours in variant order. Content is validated by the API before it is stored.
CREATE TABLE home_heroes (
    variant VARCHAR(1) PRIMARY KEY CHECK (variant IN ('a', 'b', 'c', 'd')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    content JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL
);

INSERT INTO home_heroes (variant, content) VALUES
('a', '{
  "meta": "Temporada / 01",
  "title": "Cosas bien elegidas,",
  "italic": "para cada parte de tu día.",
  "body": "Lo que escuchas, lo que habitas y lo que llevas contigo. Una selección con el mismo criterio.",
  "ctaLabel": "Explorar la tienda",
  "main": {"label": "01 / Hogar", "title": "El arte de habitar.", "imageUrl": "/products/hero.jpg",
           "alt": "Sala cálida con piezas de diseño, materiales naturales y luz de la tarde", "categorySlug": "living"},
  "tiles": [
    {"label": "02 / Audio", "title": "El sonido de tu espacio.", "imageUrl": "/products/headphones.jpg",
     "alt": "Auriculares Halo", "categorySlug": "audio"},
    {"label": "03 / Accesorios", "title": "Detalles que te acompañan.", "imageUrl": "/products/watch.jpg",
     "alt": "Reloj Línea", "categorySlug": "accesorios"}
  ]
}'),
('b', '{
  "title": null,
  "italic": "Un mismo criterio.",
  "body": "Elegimos cada pieza con la misma pregunta: ¿se va a quedar contigo?"
}'),
('c', '{
  "campaigns": [
    {"world": "Audio", "eyebrow": "Nuevo en Audio", "title": "El ritual", "italic": "de escuchar.",
     "body": "Una pieza que devuelve intención a tus discos y calidez a tu espacio.", "productSlug": "tempo"},
    {"world": "Hogar", "eyebrow": "Destacado en Hogar", "title": "La luz", "italic": "cambia todo.",
     "body": "Una lámpara de mesa para crear rincones cálidos al final del día.", "productSlug": "luna"},
    {"world": "Accesorios", "eyebrow": "Esenciales en Accesorios", "title": "Precisión", "italic": "para todos tus días.",
     "body": "Precisión de líneas y equilibrio de proporciones. Un reloj para acompañarte siempre.", "productSlug": "linea"}
  ]
}'),
('d', '{
  "title": "Tu día,",
  "italic": "mejor elegido.",
  "moments": [
    {"id": "manana", "label": "Mañana", "line": "Para salir con todo.", "imageUrl": "/products/bag.jpg",
     "alt": "Bolsa Folio", "productSlugs": ["flow", "aero", "sol"]},
    {"id": "tarde", "label": "Tarde", "line": "Para tu espacio.", "imageUrl": "/products/hero.jpg",
     "alt": "Sala cálida con luz de la tarde", "productSlugs": ["arc", "hora", "onda"]},
    {"id": "noche", "label": "Noche", "line": "Para bajar el ritmo.", "imageUrl": "/products/editorial.jpg",
     "alt": "Interior sereno con luz suave", "productSlugs": ["luna", "tempo", "halo"]}
  ]
}');

DO $$
BEGIN
    IF '${runtimeRole}' <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtimeRole}') THEN
        EXECUTE format('GRANT SELECT, UPDATE ON TABLE home_heroes TO %I', '${runtimeRole}');
    END IF;
END $$;
