-- «Newest» means newest in the shop: by the first time a product was published, not when it was made (a draft can
-- wait a month) and not its last publish (going out of the shop and back doesn't make it new).
-- Products published until now only kept their last publish; that is the best there is for them.
ALTER TABLE productqm_tbl ADD COLUMN "firstPublishedOn" TIMESTAMPTZ;
UPDATE productqm_tbl SET "firstPublishedOn" = "publishedOn";

DROP INDEX productqm_tbl_created_on_idx;
CREATE INDEX productqm_tbl_first_published_on_idx ON productqm_tbl ("firstPublishedOn");
