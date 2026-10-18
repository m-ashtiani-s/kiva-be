-- The order read model after the payment (completion roadmap, phase 2 step 2.2): what the panel's queue and the
-- customer's pages read about the photos before shipping, the parcel, the delivery, a cancellation and the money given
-- back, and a timeline entry that says what happened (its kind) instead of only the status it moved to.
--
-- Still written only by OrderQmProjector, from the order's events, in their transaction. Columns for what is filtered,
-- sorted or locked on; JSONB for what is only shown, in the shapes of the events' payloads. The CHECKs repeat the
-- aggregate's rules: a projector bug stops here instead of reaching a customer. Existing rows are filled from what they
-- already hold, so nothing needs the event store replayed.

ALTER TABLE orderqm_tbl
    -- The order's code, the recipient's name and phone and the city, as normalizePersian leaves them: what the queue's q
    -- searches besides the account's name.
    ADD COLUMN "searchText"       TEXT        NOT NULL DEFAULT '',
    ADD COLUMN "photoStatus"      VARCHAR(16) NOT NULL DEFAULT 'WAITING' CHECK ("photoStatus" IN ('WAITING', 'SENT', 'APPROVED', 'CHANGE_REQUESTED')),
    -- A photo was sent once: the customer no longer cancels it themselves.
    ADD COLUMN "photoEverSent"    BOOLEAN     NOT NULL DEFAULT FALSE,
    -- {"draft": [OrderPhotoPayload], "sent": [OrderPhotoPayload], "batch": n}: the draft is the panel's only.
    ADD COLUMN photos             JSONB       NOT NULL DEFAULT '{"draft": [], "sent": [], "batch": 0}' CHECK (jsonb_typeof(photos) = 'object'),
    ADD COLUMN "photosSentOn"     TIMESTAMPTZ,
    ADD COLUMN "feedbackDeadline" TIMESTAMPTZ,
    -- The customer's last answer to the photos: {decision, changeType, orderItemId, desiredVariantId, note, at, resolution}.
    ADD COLUMN feedback           JSONB CHECK (jsonb_typeof(feedback) = 'object'),
    ADD COLUMN "trackingCode"     VARCHAR(40) CHECK ("trackingCode" ~ '^[0-9A-Z-]{6,40}$'),
    ADD COLUMN "shippedOn"        TIMESTAMPTZ,
    -- Listed, masked, on «کدهای رهگیری روزانه».
    ADD COLUMN "showInDailyList"  BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN "deliveredOn"      TIMESTAMPTZ,
    ADD COLUMN "returnableUntil"  TIMESTAMPTZ,
    ADD COLUMN "cancelledOn"      TIMESTAMPTZ,
    -- {by, reason, note}; the note is personal, shown to the customer.
    ADD COLUMN cancellation       JSONB CHECK (jsonb_typeof(cancellation) = 'object'),
    -- What went back: the completed refunds, in toman.
    ADD COLUMN refunded           BIGINT      NOT NULL DEFAULT 0 CHECK (refunded >= 0),
    ADD CONSTRAINT orderqm_shipped_check CHECK (
        (status IN ('SHIPPED', 'DELIVERED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED') AND "shippedOn" IS NOT NULL AND "trackingCode" IS NOT NULL)
            OR status NOT IN ('SHIPPED', 'DELIVERED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED')),
    ADD CONSTRAINT orderqm_cancelled_check CHECK ((status = 'CANCELLED') = ("cancelledOn" IS NOT NULL)),
    ADD CONSTRAINT orderqm_photo_sent_check CHECK ("photoStatus" = 'WAITING' OR ("photoEverSent" AND "photosSentOn" IS NOT NULL AND "feedbackDeadline" IS NOT NULL)),
    ADD CONSTRAINT orderqm_refunded_check CHECK (refunded <= payable);

-- searchText of the rows already there, with the same rules as normalizePersian (OrderQmSearchText; a test holds both
-- to the same text): Persian and Arabic digits to Latin, ي/ى to ی, ك to ک, runs of ZWNJ to one, a ZWNJ touching a space
-- and runs of spaces to one space, and neither at the ends.
UPDATE orderqm_tbl
SET "searchText" = btrim(
        regexp_replace(
            regexp_replace(
                translate(code || ' ' || (address ->> 'recipientName') || ' ' || (address ->> 'recipientPhone') || ' ' || (address ->> 'cityName'),
                          'يىك۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩', 'ییک01234567890123456789'),
                chr(8204) || '+', chr(8204), 'g'),
            chr(8204) || '*\s+' || chr(8204) || '*', ' ', 'g'),
        ' ' || chr(8204));

-- Every timeline entry gets the kind of what happened, from its status and the entry before it: placed, a failed attempt,
-- paid (held or not), expired, and PROCESSING right after RESERVED is the reservation letting it go.
UPDATE orderqm_tbl o
SET timeline = (SELECT jsonb_agg(x.entry || jsonb_build_object('kind', x.kind) ORDER BY x.n)
                FROM (SELECT e.entry,
                             e.n,
                             CASE e.entry ->> 'status'
                                 WHEN 'PENDING_PAYMENT' THEN 'PLACED'
                                 WHEN 'PAYMENT_FAILED' THEN 'PAYMENT_FAILED'
                                 WHEN 'EXPIRED' THEN 'EXPIRED'
                                 WHEN 'RESERVED' THEN 'PAID'
                                 WHEN 'PROCESSING' THEN CASE
                                                            WHEN lag(e.entry ->> 'status') OVER (ORDER BY e.n) = 'RESERVED' THEN 'RELEASED_FROM_RESERVATION'
                                                            ELSE 'PAID' END
                                 END AS kind
                      FROM jsonb_array_elements(o.timeline) WITH ORDINALITY AS e(entry, n)) x);

-- The panel's queue: by status, newest first.
CREATE INDEX orderqm_status_placed_idx ON orderqm_tbl (status, "placedOn" DESC);
-- «کدهای رهگیری روزانه»: what was shipped on a day and is listed.
CREATE INDEX orderqm_daily_idx ON orderqm_tbl ("shippedOn") WHERE "shippedOn" IS NOT NULL AND "showInDailyList";
CREATE INDEX orderqm_tracking_idx ON orderqm_tbl ("trackingCode") WHERE "trackingCode" IS NOT NULL;
-- The approve-silent-photos job: photos sent, by the end of their time to answer.
CREATE INDEX orderqm_photo_deadline_idx ON orderqm_tbl ("feedbackDeadline") WHERE "photoStatus" = 'SENT';
-- A reservation group's orders (the panel's shipment group, shipping a group).
CREATE INDEX orderqm_group_idx ON orderqm_tbl ("reservationGroupId") WHERE "reservationGroupId" IS NOT NULL;

-- The carrier's tracking page, its {code} replaced by the parcel's code (Shipment.trackingUrl). Checked against the
-- carriers' own sites before launch (phase 6's checklist); until phase 4 changed with SQL only.
ALTER TABLE shippingmethod_tbl
    ADD COLUMN "trackingUrlTemplate" VARCHAR(300) CHECK ("trackingUrlTemplate" LIKE 'https://%{code}%');
UPDATE shippingmethod_tbl SET "trackingUrlTemplate" = 'https://tipaxco.com/tracking?code={code}' WHERE code = 'TIPAX';
UPDATE shippingmethod_tbl SET "trackingUrlTemplate" = 'https://tracking.post.ir/?id={code}' WHERE code = 'POST';
