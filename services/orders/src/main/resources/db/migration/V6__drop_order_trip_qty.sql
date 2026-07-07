-- V5 moved trip/qty onto per-leg rows (order_legs) and relaxed these columns to NULLable, but left them in
-- place. The Order entity no longer maps them, so they have been dead, always-NULL columns since. Drop them.
-- Not in the Debezium publication (dbz_outbox covers only outbox_events + heartbeat), so CDC is unaffected.
ALTER TABLE orders DROP COLUMN trip_code;
ALTER TABLE orders DROP COLUMN qty;
