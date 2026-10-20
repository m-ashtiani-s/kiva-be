-- The panel's settings, discount codes and report (completion roadmap, phase 4, section 9.4). One migration for the
-- whole phase, written in its first step (section 21): steps 4.2 to 4.4 only use what is here.
--
-- shopsettings_tbl: what was a constant or changed with SQL is now the panel's (PUT /admin/settings). The plain values
-- are columns with the same limits as ShopSettings; the lists and documents are JSONB, written whole by
-- ShopSettingsRepositoryImpl, their shape checked by the domain's value objects before they get here. The starting
-- values are what the code did until now (lowStockThreshold 5, preShipmentFeedbackHours 24), every feature off.
-- version (from V12) is now used: a save is written only over the version it was read in.
ALTER TABLE shopsettings_tbl
    ADD COLUMN "lowStockThreshold"        INTEGER      NOT NULL DEFAULT 5 CHECK ("lowStockThreshold" BETWEEN 1 AND 20),
    ADD COLUMN "preShipmentFeedbackHours" INTEGER      NOT NULL DEFAULT 24 CHECK ("preShipmentFeedbackHours" BETWEEN 1 AND 168),
    -- An Iranian mobile or landline with its area code, 11 digits (SupportPhone).
    ADD COLUMN "supportPhone"             VARCHAR(20) CHECK ("supportPhone" ~ '^0[1-9][0-9]{9}$'),
    ADD COLUMN "supportEmail"             VARCHAR(100) CHECK ("supportEmail" = lower(btrim("supportEmail")) AND "supportEmail" LIKE '%_@_%.__%'),
    -- [{label, weekdays, from, to, messengerOnly}], at most 7 (SupportHours).
    ADD COLUMN "supportHours"             JSONB        NOT NULL DEFAULT '[]' CHECK (jsonb_typeof("supportHours") = 'array' AND jsonb_array_length("supportHours") <= 7),
    -- [{channel, name, url, handle}], at most 10 (SocialLink).
    ADD COLUMN social                     JSONB        NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(social) = 'array' AND jsonb_array_length(social) <= 10),
    -- The marketing numbers, typed by hand (SiteStats); averageRating in tenths (49 is 4.9), never a float.
    ADD COLUMN "siteStats"                JSONB        NOT NULL DEFAULT '{"happyCustomers":0,"preShipmentPhotos":0,"photoMatchSatisfactionPercent":0,"productModels":0,"satisfaction":null}'
        CHECK (jsonb_typeof("siteStats") = 'object'),
    -- Only the keys the shop knows (ShopFeatures); gift wrapping can't be switched on yet.
    ADD COLUMN features                   JSONB        NOT NULL DEFAULT '{"wishlistShare":false,"reviewMedia":false,"giftWrap":false}'
        CHECK (jsonb_typeof(features) = 'object' AND features ?& ARRAY ['wishlistShare', 'reviewMedia', 'giftWrap']
            AND features - 'wishlistShare' - 'reviewMedia' - 'giftWrap' = '{}'::jsonb AND features ->> 'giftWrap' = 'false'),
    -- The same limit as ShopSettings (decision 4).
    ADD CONSTRAINT "shopsettings_tbl_freeShippingThreshold_max_check" CHECK ("freeShippingThreshold" <= 100000000);

-- A shipping method's name and cost are the panel's now too (ShippingMethodName, decision 4).
ALTER TABLE shippingmethod_tbl
    ADD CONSTRAINT shippingmethod_tbl_name_length_check CHECK (length(name) <= 50 AND name = btrim(name)),
    ADD CONSTRAINT "shippingmethod_tbl_baseCost_max_check" CHECK ("baseCost" <= 10000000),
    ADD CONSTRAINT shippingmethod_tbl_icon_format_check CHECK (icon ~ '^[a-z0-9-]{1,30}$'),
    ADD CONSTRAINT shippingmethod_tbl_min_days_max_check CHECK ("minDays" <= 30);

-- discountcode_tbl: the panel makes and edits codes (step 4.2). version for two saves at once (409), updatedOn for the
-- panel; a code made before this migration was last changed when it was made.
ALTER TABLE discountcode_tbl
    ADD COLUMN version     BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN "updatedOn" TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE discountcode_tbl SET "updatedOn" = "createdOn";

-- The panel's list: newest made first.
CREATE INDEX discountcode_created_idx ON discountcode_tbl ("createdOn" DESC, "pId" DESC);

-- orderqm_tbl: what the report counts (step 4.4), kept by OrderQmProjector from now on and filled here from the orders'
-- streams for what happened before.
--   firstPhotosSentOn    when the order's first photos were sent (its first OrderPhotosSent)
--   changeRequestedEver  the customer ever asked for a change (any OrderChangeRequested), whatever came of it
ALTER TABLE orderqm_tbl
    ADD COLUMN "firstPhotosSentOn"   TIMESTAMPTZ,
    ADD COLUMN "changeRequestedEver" BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE orderqm_tbl o
   SET "firstPhotosSentOn" = e.first
  FROM (SELECT "aggregateId", min("occurredOn") AS first
          FROM eventstore_tbl
         WHERE "aggregateName" = 'ORDER' AND "eventType" = 'OrderPhotosSent'
         GROUP BY "aggregateId") e
 WHERE e."aggregateId" = o."orderId"::text;

UPDATE orderqm_tbl o
   SET "changeRequestedEver" = TRUE
 WHERE EXISTS (SELECT 1
                 FROM eventstore_tbl e
                WHERE e."aggregateName" = 'ORDER' AND e."eventType" = 'OrderChangeRequested' AND e."aggregateId" = o."orderId"::text);

-- The report reads the orders paid in a range of days.
CREATE INDEX orderqm_paid_on_idx ON orderqm_tbl ("paidOn") WHERE "paidOn" IS NOT NULL;
CREATE INDEX orderqm_first_photos_sent_idx ON orderqm_tbl ("firstPhotosSentOn") WHERE "firstPhotosSentOn" IS NOT NULL;
