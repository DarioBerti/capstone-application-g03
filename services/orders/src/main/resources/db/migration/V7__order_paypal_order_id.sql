-- Card-Fields checkout: the PayPal order the browser creates + approves (card entered in PayPal's iframe) at pay.
-- Persisted on the order so a timeout-sweeper re-drive of authorize-payment re-sends the SAME approved order id
-- (the async authorize step acts on this order instead of creating one). Nullable: the mock/legacy server-side
-- card path leaves it null.
ALTER TABLE orders ADD COLUMN paypal_order_id VARCHAR(64);
