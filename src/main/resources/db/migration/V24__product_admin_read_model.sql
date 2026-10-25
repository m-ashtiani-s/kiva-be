-- What the panel's product list and product page read (phase 1, step 1.4). Still the projectors' table only; every new
-- column is filled here from what is already stored (no read model rebuild tool: roadmap section 1, row 18), and the
-- projectors keep it from now on.
--
-- version          the product's version: the sequence of its last event (the ETag of the panel's form)
-- createdByUserId  who made it (0 = the system, as the seeder)
-- publishAt        a draft's scheduled publication (step 1.6); only a draft has one
-- tags, seo        the product form's new fields (step 1.5); empty until then
-- availableTotal   what its active colors can still sell together (on hand minus reserved): the panel's stock and its sort
-- barcode          a color's barcode (step 1.5)
-- soldCount        how many of a color were sold, as productqm_tbl."soldCount" is for the product

ALTER TABLE productqm_tbl
    ADD COLUMN version           BIGINT      NOT NULL DEFAULT 1 CHECK (version >= 1),
    ADD COLUMN "createdByUserId" BIGINT      NOT NULL DEFAULT 0 CHECK ("createdByUserId" >= 0),
    ADD COLUMN "publishAt"       TIMESTAMPTZ,
    ADD COLUMN tags              TEXT[]      NOT NULL DEFAULT '{}',
    ADD COLUMN seo               JSONB       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(seo) = 'object'),
    ADD COLUMN "availableTotal"  INTEGER     NOT NULL DEFAULT 0 CHECK ("availableTotal" >= 0),
    ADD CONSTRAINT productqm_publish_at_only_draft CHECK ("publishAt" IS NULL OR status = 'DRAFT');

ALTER TABLE productvariantqm_tbl
    ADD COLUMN barcode     VARCHAR(32),
    ADD COLUMN "soldCount" INTEGER NOT NULL DEFAULT 0 CHECK ("soldCount" >= 0);

UPDATE productqm_tbl p SET version = e.v
  FROM (SELECT "aggregateId", max(sequence) AS v FROM eventstore_tbl WHERE "aggregateName" = 'PRODUCT' GROUP BY "aggregateId") e
 WHERE e."aggregateId" = p."productId"::text;

UPDATE productqm_tbl p SET "createdByUserId" = e."occurredByUserId"
  FROM eventstore_tbl e
 WHERE e."aggregateName" = 'PRODUCT' AND e."eventType" = 'ProductCreated' AND e."aggregateId" = p."productId"::text;

UPDATE productqm_tbl p SET "availableTotal" = coalesce(v.total, 0)
  FROM (SELECT "productId", sum("onHand" - reserved) AS total FROM productvariantqm_tbl WHERE "isActive" GROUP BY "productId") v
 WHERE v."productId" = p."productId";

UPDATE productvariantqm_tbl v SET "soldCount" = s.sold
  FROM (SELECT (l->>'variantId')::bigint AS "variantId", sum((l->>'quantity')::int) AS sold
          FROM orderqm_tbl o, jsonb_array_elements(o.lines) l
         WHERE o."paidOn" IS NOT NULL GROUP BY 1) s
 WHERE s."variantId" = v."variantId";

-- The search text now ends with the product's code (phase decision 17), as ProductQmSummary writes it from now on.
UPDATE productqm_tbl SET "searchText" = "searchText" || ' ' || sku;

CREATE INDEX productqm_status_updated_idx ON productqm_tbl (status, "updatedOn" DESC);
CREATE INDEX productqm_publish_due_idx ON productqm_tbl ("publishAt") WHERE "publishAt" IS NOT NULL;
