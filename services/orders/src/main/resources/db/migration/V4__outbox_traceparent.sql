-- Fase 4 (cross-Kafka tracing): the app no longer produces to Kafka (Debezium CDC does), so the W3C trace
-- context is persisted with the event in the SAME transaction and the Debezium Outbox Event Router maps it
-- to the `traceparent` Kafka header (table.fields.additional.placement). NULL = no active span at append
-- time (e.g. a timeout-sweeper re-drive) -> the consumer starts a fresh trace.
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(64);
