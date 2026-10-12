-- Back from the gateway (purchase step 14): at most one attempt of an order is being confirmed at a time.
--
-- An attempt is confirmed (the gateway's verify, which takes the money) only while its order still waits for its money
-- and no other attempt of the order is being confirmed; PaymentDomainService checks it on every attempt of the order,
-- locked together. This index is the second wall: two attempts that came back paid at the same moment can never both
-- be VERIFYING, so the money of the second is never taken and the gateway gives it back by itself. The index of
-- V16 (one success per order) only stops the record of a second success, after its money was taken.
CREATE UNIQUE INDEX payment_one_verifying_per_order_idx ON payment_tbl ("orderId") WHERE status = 'VERIFYING';
