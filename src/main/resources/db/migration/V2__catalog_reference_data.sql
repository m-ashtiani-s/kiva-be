-- The starting palette, categories and materials, taken from the frontend prototype (assets/kiva.js).
-- Colors and categories keep the prototype's order; that order is their sortOrder.
-- Light colors (isLight) are the ones the contract names: white, cream, pink, lilac, gray.

INSERT INTO color_tbl ("colorKey", name, hex, "isLight", "sortOrder")
VALUES ('black', 'مشکی', '#2B2930', FALSE, 1),
       ('cream', 'کرم', '#EADFCB', TRUE, 2),
       ('brown', 'قهوه‌ای', '#8A5A3C', FALSE, 3),
       ('lilac', 'یاسی', '#C8B6E2', TRUE, 4),
       ('gray', 'طوسی', '#A6A3AD', TRUE, 5),
       ('olive', 'زیتونی', '#7D8150', FALSE, 6),
       ('red', 'زرشکی', '#A8344A', FALSE, 7),
       ('pink', 'صورتی', '#EDB9C6', TRUE, 8),
       ('blue', 'آبی', '#5677AE', FALSE, 9),
       ('white', 'سفید', '#F8F6F2', TRUE, 10),
       ('caramel', 'کاراملی', '#C68B59', FALSE, 11),
       ('navy', 'سرمه‌ای', '#2F3B5C', FALSE, 12),
       ('purple', 'بنفش', '#5B3E8C', FALSE, 13);

INSERT INTO category_tbl (slug, name, "iconBagType", "iconColorKey", "sortOrder", "showInMenu")
VALUES ('shoulder', 'کیف دوشی', 'HOBO', 'lilac', 1, TRUE),
       ('hand', 'کیف دستی', 'SATCHEL', 'caramel', 2, TRUE),
       ('cross', 'کراس‌بادی', 'CROSSBODY', 'red', 3, TRUE),
       ('backpack', 'کوله‌پشتی', 'BACKPACK', 'olive', 4, TRUE),
       ('party', 'کیف مجلسی', 'CLUTCH', 'black', 5, TRUE),
       ('tote', 'شاپر و توت', 'TOTE', 'cream', 6, TRUE),
       ('bucket', 'کیف سطلی', 'BUCKET', 'pink', 7, TRUE),
       ('wallet', 'کیف پول', 'WALLET', 'purple', 8, TRUE);

-- The prototype only has a free-text material per product. These are the materials behind those texts;
-- details such as "with golden chain" stay in the product's own materialDescription.
-- Care instructions are not written yet, so they start empty.
INSERT INTO material_tbl (slug, name, family)
VALUES ('genuine-cow-leather', 'چرم طبیعی گاوی', 'GENUINE_LEATHER'),
       ('genuine-goat-leather', 'چرم طبیعی بزی', 'GENUINE_LEATHER'),
       ('genuine-leather', 'چرم طبیعی', 'GENUINE_LEATHER'),
       ('faux-leather-matte', 'چرم مصنوعی مات درجه یک', 'FAUX_LEATHER'),
       ('faux-leather-soft', 'چرم مصنوعی سافت', 'FAUX_LEATHER'),
       ('faux-leather-glossy', 'چرم مصنوعی براق', 'FAUX_LEATHER'),
       ('faux-leather', 'چرم مصنوعی', 'FAUX_LEATHER'),
       ('waterproof-canvas', 'برزنت ضدآب', 'CANVAS'),
       ('thick-cotton', 'کتان ضخیم', 'COTTON'),
       ('faux-suede', 'جیر مصنوعی', 'FAUX_SUEDE'),
       ('satin', 'ساتن', 'SATIN'),
       ('velvet', 'مخمل', 'VELVET');
