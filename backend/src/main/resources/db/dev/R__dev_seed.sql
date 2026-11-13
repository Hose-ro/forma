-- Development seed data (profile 'dev' only). Idempotent: re-running never duplicates rows
-- and never overwrites data edited through the admin API.

INSERT INTO categories (id, slug, name, description, image_url, sort_order) VALUES
    (gen_random_uuid(), 'audio',      'Audio',      'El sonido de tu espacio.',   '/products/headphones.jpg', 1),
    (gen_random_uuid(), 'living',     'Living',     'Objetos para habitar.',      '/products/hero.jpg',       2),
    (gen_random_uuid(), 'accesorios', 'Accesorios', 'Detalles que te acompañan.', '/products/watch.jpg',      3)
ON CONFLICT (slug) DO NOTHING;

WITH seed (slug, name, category_slug, price, photo, featured, days_ago, stock, description) AS (VALUES
    ('halo',  'Halo Headphones',   'audio',      2490.00, 'headphones', TRUE,  1,  12, 'Tu propio espacio de sonido. Auriculares de líneas limpias, almohadillas envolventes y un acabado que invita a escuchar sin prisa.'),
    ('onda',  'Onda Speaker',      'audio',      1890.00, 'speaker',    FALSE, 2,  11, 'Un pequeño objeto, una presencia sonora generosa. Sonido para acompañar los momentos cotidianos.'),
    ('aero',  'Aero Earbuds',      'audio',      1290.00, 'earbuds',    FALSE, 3,  10, 'Ligereza en cada nota. Auriculares compactos para llevar tu música a cualquier lugar.'),
    ('tempo', 'Tempo Turntable',   'audio',      4290.00, 'turntable',  FALSE, 4,   9, 'El ritual de escuchar. Una pieza que devuelve intención a tus discos y calidez a tu espacio.'),
    ('arc',   'Arc Chair',         'living',     5490.00, 'chair',      TRUE,  5,   8, 'Una silueta escultórica para habitar. Curvas suaves y una estructura que encuentra belleza en lo esencial.'),
    ('luna',  'Luna Table Light',  'living',     1690.00, 'lamp',       TRUE,  6,   7, 'La luz cambia todo. Una lámpara de mesa para crear rincones cálidos al final del día.'),
    ('terra', 'Terra Vase',        'living',      890.00, 'vase',       TRUE,  7,   6, 'Textura, volumen y calma. Un jarrón que funciona como pieza escultórica, con o sin flores.'),
    ('hora',  'Hora Clock',        'living',     1190.00, 'clock',      FALSE, 8,   5, 'Una pausa en la pared. Geometría sencilla para contemplar el paso del tiempo.'),
    ('folio', 'Folio Everyday Bag','accesorios', 1590.00, 'bag',        FALSE, 9,   4, 'Todo lo necesario, en su lugar. Una bolsa de uso diario con proporciones pensadas para acompañarte.'),
    ('linea', 'Línea Watch',       'accesorios', 2190.00, 'watch',      FALSE, 10,  3, 'Precisión de líneas y equilibrio de proporciones. Un reloj para todos tus días.'),
    ('sol',   'Sol Sunglasses',    'accesorios',  990.00, 'sunglasses', FALSE, 11,  2, 'Una mirada distinta. Una montura atemporal con una presencia sutil.'),
    ('flow',  'Flow Bottle',       'accesorios',  590.00, 'bottle',     FALSE, 12,  1, 'Un gesto cotidiano, bien diseñado. Una botella reutilizable para acompañar el ritmo de tu día.')
),
inserted AS (
    INSERT INTO products (id, category_id, slug, name, description, material, status, featured, price, currency, created_at, updated_at)
    SELECT gen_random_uuid(), c.id, s.slug, s.name, s.description,
           'Especificaciones de ejemplo.', 'ACTIVE', s.featured, s.price, 'MXN',
           now() - make_interval(days => s.days_ago), now()
    FROM seed s JOIN categories c ON c.slug = s.category_slug
    ON CONFLICT (slug) DO NOTHING
    RETURNING id, slug, price
),
images AS (
    INSERT INTO product_images (id, product_id, url, alt_text, position)
    SELECT gen_random_uuid(), i.id, '/products/' || s.photo || '.jpg', s.name, 0
    FROM inserted i JOIN seed s ON s.slug = i.slug
)
INSERT INTO product_variants (id, product_id, sku, name, color, price, currency, stock, position, image_url)
SELECT gen_random_uuid(), i.id, upper(s.slug) || v.suffix, v.name, v.color,
       s.price + v.delta, 'MXN',
       CASE WHEN v.suffix = '-ORI' THEN s.stock WHEN s.slug = 'aero' THEN 0 ELSE 4 END,
       v.position, '/products/' || s.photo || '.jpg'
FROM inserted i
JOIN seed s ON s.slug = i.slug
CROSS JOIN (VALUES ('-ORI', 'Original', '#b6aa94', 0.00, 0),
                   ('-EDS', 'Edición especial', '#363b32', 200.00, 1)) AS v(suffix, name, color, delta, position)
ON CONFLICT (sku) DO NOTHING;
