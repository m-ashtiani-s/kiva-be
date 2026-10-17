-- The panel writes categories, colors and materials (phase 1, step 1.3). Same plain tables as V1; this adds what the
-- panel's forms have and the shop shows: a category's photo (a media row's public id, no foreign key because an
-- attached media row is never removed) and its SEO fields. The CHECKs repeat the value objects' limits
-- (CategoryDescription, MaterialNote, CareInstructions); the use cases check first and answer 400.

ALTER TABLE category_tbl
    ADD COLUMN "imageMediaId"   VARCHAR(26) CHECK ("imageMediaId" ~ '^med_[0-9A-Za-z]{22}$'),
    ADD COLUMN "seoTitle"       VARCHAR(70),
    ADD COLUMN "seoDescription" VARCHAR(170),
    ADD COLUMN "seoNoIndex"     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT category_description_length CHECK (char_length(description) <= 500);

ALTER TABLE material_tbl
    ADD CONSTRAINT material_description_length CHECK (char_length(description) <= 1000),
    ADD CONSTRAINT material_care_count CHECK (cardinality("careInstructions") <= 10);
