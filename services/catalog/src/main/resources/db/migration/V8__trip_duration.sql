-- Sortable journey duration for the storefront's "Fastest" ordering. Spring Data's Pageable sorting only
-- binds to entity properties, and arrival - departure isn't one — a STORED generated column makes the
-- duration a real, DB-owned column (always consistent with the two timestamps, computed for existing rows
-- at ALTER time) so `sort=durationMinutes` paginates correctly like any other property.
ALTER TABLE trips ADD COLUMN duration_minutes integer GENERATED ALWAYS AS
    ((EXTRACT(EPOCH FROM (arrival_time - departure_time)) / 60)::integer) STORED;
