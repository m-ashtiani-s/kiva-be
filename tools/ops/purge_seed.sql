-- Removes the prototype's sample data and everything the tests on the server bought, before real selling starts
-- (LAUNCH.md, step 6.7). Run it only through tools/ops/purge-seed.sh, which stops nothing by itself: it refuses while
-- the app runs, makes a dump first, and wraps this file in one transaction (ROLLBACK for the dry run, COMMIT with
-- --apply). This file has no BEGIN or COMMIT of its own, so a test can run it in its own transaction too
-- (PurgeSeedSqlOnDatabaseTest). Never run it after the first real order: every order, payment and refund goes.
--
-- What goes:
--   the 16 sample bags (DevCatalogSeeder), matched by code and address together: their events, read model, colors,
--     stock history, and their colors in anyone's cart
--   every order, payment (with its log), refund, order note, reservation group, code use and Idempotency-Key: before
--     launch they are all tests
--   the change-log rows of those orders, refunds, bags, colors and stock
--   the three sample codes (KIVA10, WELCOME, PAEEZ15), only while their label is still the seeded one
-- What is released: the photos sent for those orders, and the bags' photos nothing else uses (attachedOn = NULL; the
--   nightly delete-unattached-media job removes them with their files 48 hours after their upload)
-- What stays: real products and their stock, categories, colors, materials, users and their addresses, settings,
--   pages, the FAQ, the announcement bar, security events. No sequence is reset: order and product numbers go on.
--
-- It refuses (and changes nothing) while a payment is still open, or while a test order still holds stock of a real
-- product: cancel that order in the panel first. A real product whose stock went down for a test order is only named:
-- correct it with «تغییر موجودی» in the panel if the bag is still on the shelf.

CREATE TEMP TABLE purge_seed_bag (sku VARCHAR(80), slug VARCHAR(200)) ON COMMIT DROP;
INSERT INTO purge_seed_bag (sku, slug)
VALUES ('KV-1001', 'mahak-shoulder-bag'), ('KV-1002', 'nila-crossbody-bag'), ('KV-1003', 'sora-backpack'),
       ('KV-1004', 'aida-handbag'), ('KV-1005', 'shabtab-clutch'), ('KV-1006', 'rozhan-shopper-bag'),
       ('KV-1007', 'parnia-bucket-bag'), ('KV-1008', 'termeh-wallet'), ('KV-1009', 'hasti-shoulder-bag'),
       ('KV-1010', 'delaram-crossbody-bag'), ('KV-1011', 'barana-backpack'), ('KV-1012', 'gelareh-handbag'),
       ('KV-1013', 'mahtab-clutch'), ('KV-1014', 'aseman-tote-bag'), ('KV-1015', 'nazli-wallet'),
       ('KV-1016', 'sheyda-bucket-bag');

CREATE TEMP TABLE purge_seed_product ON COMMIT DROP AS
SELECT p."productId"
  FROM productqm_tbl p
  JOIN purge_seed_bag b ON b.sku = p.sku AND b.slug = p.slug;

CREATE TEMP TABLE purge_seed_variant ON COMMIT DROP AS
SELECT v."variantId"
  FROM productvariantqm_tbl v
 WHERE v."productId" IN (SELECT "productId" FROM purge_seed_product);

-- Every stock hold an order made on a color, and how much of it is still held (reserved, minus released, minus shipped).
CREATE TEMP TABLE purge_seed_hold ON COMMIT DROP AS
SELECT e."aggregateId"::bigint AS "variantId",
       e.payload ->> 'orderCode' AS "orderCode",
       sum(CASE e."eventType" WHEN 'StockReserved' THEN (e.payload ->> 'quantity')::int ELSE 0 END)
           - sum(CASE e."eventType" WHEN 'StockReserved' THEN 0 ELSE (e.payload ->> 'quantity')::int END) AS held,
       sum(CASE e."eventType" WHEN 'StockShipped' THEN (e.payload ->> 'quantity')::int ELSE 0 END) AS shipped
  FROM eventstore_tbl e
 WHERE e."aggregateName" = 'INVENTORY'
   AND e."eventType" IN ('StockReserved', 'ReservationReleased', 'StockShipped')
 GROUP BY 1, 2;

DO $$
DECLARE
    open_payments INT;
    held_real     TEXT;
    look_alike    RECORD;
    shipped_real  RECORD;
BEGIN
    SELECT count(*) INTO open_payments FROM payment_tbl WHERE status IN ('INITIATED', 'REDIRECTED', 'VERIFYING');
    IF open_payments > 0 THEN
        RAISE EXCEPTION 'purge_seed: % payment(s) are still open (INITIATED, REDIRECTED or VERIFYING); nothing was removed. The jobs settle them within 20 minutes: start the app, wait, stop it and run this again', open_payments;
    END IF;

    SELECT string_agg(DISTINCT v.sku || ' for ' || h."orderCode", ', ') INTO held_real
      FROM purge_seed_hold h
      JOIN productvariantqm_tbl v ON v."variantId" = h."variantId"
     WHERE h.held > 0 AND h."variantId" NOT IN (SELECT "variantId" FROM purge_seed_variant);
    IF held_real IS NOT NULL THEN
        RAISE EXCEPTION 'purge_seed: test orders still hold stock of real products (%); nothing was removed. Cancel those orders in the panel, then run this again', held_real;
    END IF;

    FOR look_alike IN
        SELECT p.sku, p.slug
          FROM productqm_tbl p
         WHERE (p.sku IN (SELECT sku FROM purge_seed_bag) OR p.slug IN (SELECT slug FROM purge_seed_bag))
           AND p."productId" NOT IN (SELECT "productId" FROM purge_seed_product)
    LOOP
        RAISE NOTICE 'purge_seed: % (%) has a sample bag''s code or address but not both, so it stays; archive it in the panel if it is a sample', look_alike.sku, look_alike.slug;
    END LOOP;

    FOR shipped_real IN
        SELECT v.sku, sum(h.shipped) AS quantity
          FROM purge_seed_hold h
          JOIN productvariantqm_tbl v ON v."variantId" = h."variantId"
         WHERE h.shipped > 0 AND h."variantId" NOT IN (SELECT "variantId" FROM purge_seed_variant)
         GROUP BY v.sku
    LOOP
        RAISE NOTICE 'purge_seed: test orders shipped % of real color %; if those bags are still on the shelf, add them back with «تغییر موجودی» in the panel', shipped_real.quantity, shipped_real.sku;
    END LOOP;
END
$$;

-- The three append-only walls, lowered for this transaction only: a rollback, or the ENABLE below, puts them back.
ALTER TABLE paymentlog_tbl DISABLE TRIGGER paymentlog_no_change;
ALTER TABLE ordernote_tbl DISABLE TRIGGER ordernote_no_change;
ALTER TABLE auditlog_tbl DISABLE TRIGGER auditlog_guard;

DO $$
DECLARE
    n_bags      INT;
    n_colors    INT;
    n_orders    INT;
    n_payments  INT;
    n_refunds   INT;
    n_notes     INT;
    n_audit     INT;
    n_codes     INT;
    n_released  INT;
    n_cart      INT;
    sample_code BIGINT[];
BEGIN
    SELECT count(*) INTO n_bags FROM purge_seed_product;
    SELECT count(*) INTO n_colors FROM purge_seed_variant;

    -- Uploads first, while the bags' galleries can still be read.
    UPDATE media_tbl SET "attachedOn" = NULL
     WHERE "attachedOn" IS NOT NULL
       AND (purpose = 'PRE_SHIPMENT'
            OR (purpose = 'PRODUCT'
                AND "mediaId" IN (SELECT m ->> 'mediaId'
                                    FROM productvariantqm_tbl v, jsonb_array_elements(v.media) m
                                   WHERE v."variantId" IN (SELECT "variantId" FROM purge_seed_variant))
                AND "mediaId" NOT IN (SELECT m ->> 'mediaId'
                                        FROM productvariantqm_tbl v, jsonb_array_elements(v.media) m
                                       WHERE v."variantId" NOT IN (SELECT "variantId" FROM purge_seed_variant)
                                         AND m ->> 'mediaId' IS NOT NULL)));
    GET DIAGNOSTICS n_released = ROW_COUNT;

    DELETE FROM cartitem_tbl WHERE "variantId" IN (SELECT "variantId" FROM purge_seed_variant);
    GET DIAGNOSTICS n_cart = ROW_COUNT;

    DELETE FROM idempotencykey_tbl;
    DELETE FROM discountredemption_tbl;
    DELETE FROM ordernote_tbl;
    GET DIAGNOSTICS n_notes = ROW_COUNT;
    DELETE FROM refund_tbl;
    GET DIAGNOSTICS n_refunds = ROW_COUNT;
    DELETE FROM paymentlog_tbl;
    DELETE FROM payment_tbl;
    GET DIAGNOSTICS n_payments = ROW_COUNT;
    DELETE FROM reservationgroup_tbl;
    DELETE FROM orderqm_tbl;
    GET DIAGNOSTICS n_orders = ROW_COUNT;
    DELETE FROM eventstore_tbl WHERE "aggregateName" = 'ORDER';

    DELETE FROM eventstore_tbl
     WHERE "aggregateName" = 'INVENTORY' AND "aggregateId" IN (SELECT "variantId"::text FROM purge_seed_variant);
    DELETE FROM eventstore_tbl
     WHERE "aggregateName" = 'PRODUCT' AND "aggregateId" IN (SELECT "productId"::text FROM purge_seed_product);
    DELETE FROM inventoryadjustmentqm_tbl WHERE "variantId" IN (SELECT "variantId" FROM purge_seed_variant);

    -- With no order left, nothing has been sold.
    UPDATE productvariantqm_tbl SET "soldCount" = 0 WHERE "soldCount" <> 0;
    UPDATE productqm_tbl SET "soldCount" = 0 WHERE "soldCount" <> 0;

    SELECT coalesce(array_agg(c."pId"), '{}') INTO sample_code
      FROM discountcode_tbl c
      JOIN (VALUES ('KIVA10', '۱۰٪ تخفیف روی کل سبد'),
                   ('WELCOME', '۱۵۰٬۰۰۰ تومان تخفیف اولین خرید'),
                   ('PAEEZ15', '۱۵٪ تخفیف پاییزه (خرید بالای ۲ میلیون)')) s (code, label)
        ON s.code = c.code AND s.label = c.label;

    DELETE FROM auditlog_tbl
     WHERE "entityType" IN ('ORDER', 'REFUND')
        OR ("entityType" = 'PRODUCT' AND "entityId" IN (SELECT "productId"::text FROM purge_seed_product))
        OR ("entityType" IN ('VARIANT', 'INVENTORY') AND "entityId" IN (SELECT "variantId"::text FROM purge_seed_variant))
        OR ("entityType" = 'DISCOUNT_CODE' AND "entityId" IN (SELECT unnest(sample_code)::text));
    GET DIAGNOSTICS n_audit = ROW_COUNT;

    UPDATE cart_tbl SET "discountCode" = NULL
     WHERE "discountCode" IN (SELECT code FROM discountcode_tbl WHERE "pId" = ANY (sample_code));
    DELETE FROM discountcode_tbl WHERE "pId" = ANY (sample_code);
    GET DIAGNOSTICS n_codes = ROW_COUNT;

    DELETE FROM productvariantqm_tbl WHERE "variantId" IN (SELECT "variantId" FROM purge_seed_variant);
    DELETE FROM productqm_tbl WHERE "productId" IN (SELECT "productId" FROM purge_seed_product);

    RAISE NOTICE 'purge_seed: % sample bags (% colors, % of them in carts), % orders, % payments, % refunds, % notes, % change-log rows, % sample codes removed; % uploads released',
        n_bags, n_colors, n_cart, n_orders, n_payments, n_refunds, n_notes, n_audit, n_codes, n_released;
END
$$;

ALTER TABLE paymentlog_tbl ENABLE TRIGGER paymentlog_no_change;
ALTER TABLE ordernote_tbl ENABLE TRIGGER ordernote_no_change;
ALTER TABLE auditlog_tbl ENABLE TRIGGER auditlog_guard;
