-- A change request after the photos, and paying what it leaves owed (completion roadmap, phase 3, section 8.4).
--
-- payment_tbl: every attempt now says what it pays. ORDER is the order itself (every attempt before this migration);
-- BALANCE is what the order owes after a change to a dearer color. An order is paid once (V16's index, now for ORDER
-- only), but may have its balance paid after each change; a BALANCE attempt is confirmed only while the order still
-- owes exactly its amount (PaymentDomainService), under the lock of the order's attempts. V18's index stays as it is: one
-- attempt of an order is being confirmed at a time, whatever it pays.
ALTER TABLE payment_tbl
    ADD COLUMN purpose VARCHAR(7) NOT NULL DEFAULT 'ORDER' CHECK (purpose IN ('ORDER', 'BALANCE'));

DROP INDEX payment_one_success_per_order_idx;
CREATE UNIQUE INDEX payment_one_order_success_idx ON payment_tbl ("orderId") WHERE purpose = 'ORDER' AND status IN ('SUCCEEDED', 'REFUNDED');

-- orderqm_tbl: the order's money after its changes (OrderTotalsView). Its lines are the lines now (a swapped line in its
-- place, a line taken out gone); totals stay the placed ones, a financial record. No order had a change before this
-- migration, so 0 is right for every row there.
--   balanceDue           what it still owes after a change to a dearer color
--   balancePaid          what was paid for changes on top of the placed payable
--   codeDiscountRemoved  the discount code's share of the lines taken out (decision 12 of section 8.2)
-- The last wall of "it never ships while something is owed": the Order aggregate refuses it first.
ALTER TABLE orderqm_tbl
    ADD COLUMN "balanceDue"          BIGINT NOT NULL DEFAULT 0 CHECK ("balanceDue" >= 0),
    ADD COLUMN "balancePaid"         BIGINT NOT NULL DEFAULT 0 CHECK ("balancePaid" >= 0),
    ADD COLUMN "codeDiscountRemoved" BIGINT NOT NULL DEFAULT 0 CHECK ("codeDiscountRemoved" >= 0),
    ADD CONSTRAINT orderqm_no_ship_with_balance CHECK ("balanceDue" = 0 OR status NOT IN ('SHIPPED', 'DELIVERED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED'));
