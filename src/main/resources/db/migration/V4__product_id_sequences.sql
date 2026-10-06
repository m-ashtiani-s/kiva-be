-- Ids for products and their colors. A product has no row of its own, only events, so nothing hands it an id the way
-- an IDENTITY column would: ProductIdGeneratorImpl takes the next number from these before the product is created.
--
-- A sequence never gives one number twice, even to two requests at the same moment ("the largest id + 1" would).
-- A number taken by a transaction that then rolls back is simply skipped: ids may have gaps, never repeats.

CREATE SEQUENCE productid_seq AS BIGINT START WITH 1;

CREATE SEQUENCE variantid_seq AS BIGINT START WITH 1;
