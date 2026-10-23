-- The live search (completion roadmap, phase 12, section 17.4).
--
-- kiva_normalize_persian: the rules of normalizePersian in SQL (Persian and Arabic digits to Latin, ي/ى to ی, ك to ک,
-- runs of ZWNJ to one, a ZWNJ touching a space and runs of spaces to one space, neither at the ends), as V26 wrote them
-- inline. A test holds it and the Kotlin function to the same text for real names (SearchMigrationTest).
CREATE FUNCTION kiva_normalize_persian(input TEXT) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS
$$
SELECT btrim(
               regexp_replace(
                       regexp_replace(
                               translate(input, 'يىك۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩', 'ییک01234567890123456789'),
                               chr(8204) || '+', chr(8204), 'g'),
                       chr(8204) || '*\s+' || chr(8204) || '*', ' ', 'g'),
               ' ' || chr(8204))
$$;

-- The product's name, normalized like searchText: the suggestions put products whose name has every word first
-- (decision 4). The projectors write it with searchText from now on; the rows already there get it here.
ALTER TABLE productqm_tbl
    ADD COLUMN "searchName" TEXT NOT NULL DEFAULT '';
UPDATE productqm_tbl
SET "searchName" = kiva_normalize_persian(name);

-- The search panel's suggested queries (decision 13): a list typed by hand, no search log is kept.
UPDATE sitecontent_tbl
SET document    = document || jsonb_build_object('search', jsonb_build_object('popularQueries', '["کیف مجلسی", "کوله دانشگاه", "کراس‌بادی مشکی"]'::jsonb)),
    "updatedOn" = now();
