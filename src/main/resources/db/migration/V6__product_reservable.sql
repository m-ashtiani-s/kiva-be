-- A product may be held in a 4-day reservation (contract 1.2.0). Every product was reservable until now.
ALTER TABLE productqm_tbl ADD COLUMN reservable BOOLEAN NOT NULL DEFAULT TRUE;
