-- The eNamad badge (and Samandehi's, once there is one) in GET /config's trustBadges (LAUNCH.md, step 6.7). The panel
-- has no form for sitecontent_tbl yet, so it is SQL. The links come from the eNamad panel's «کد نماد» snippet: the
-- href of its <a> is enamad_link, the src of its <img> is enamad_image. As root on the server:
--
--   runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -d kiva \
--       -v enamad_link='https://trustseal.enamad.ir/?id=…&Code=…' \
--       -v enamad_image='https://trustseal.enamad.ir/logo.aspx?id=…&Code=…' \
--       [-v samandehi_link='…' -v samandehi_image='…'] \
--       -f /opt/kiva-ops/trust_badges.sql
--
-- Running it again replaces the badges. GET /config is cached for 5 minutes (and by the CDN, if it caches it).

\if :{?enamad_link}
\else
    \echo 'trust_badges: -v enamad_link=… and -v enamad_image=… are required'
    \quit
\endif

BEGIN;

CREATE TEMP TABLE trust_badge (type TEXT, label TEXT, "imageUrl" TEXT, "linkUrl" TEXT) ON COMMIT DROP;
INSERT INTO trust_badge VALUES ('ENAMAD', 'نماد اعتماد الکترونیکی', :'enamad_image', :'enamad_link');
\if :{?samandehi_link}
INSERT INTO trust_badge VALUES ('SAMANDEHI', 'نشان ملی ثبت', :'samandehi_image', :'samandehi_link');
\endif

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trust_badge WHERE "imageUrl" !~ '^https://[^\s]+$' OR "linkUrl" !~ '^https://[^\s]+$') THEN
        RAISE EXCEPTION 'trust_badges: every link and image must be a whole https:// address';
    END IF;
END
$$;

UPDATE sitecontent_tbl
   SET document = jsonb_set(document, '{trustBadges}', (SELECT jsonb_agg(to_jsonb(b) ORDER BY b.type) FROM trust_badge b)),
       "updatedOn" = now();

SELECT jsonb_pretty(document -> 'trustBadges') AS "trustBadges" FROM sitecontent_tbl;

COMMIT;
