-- Seat counts for the round-trip demo trips seeded in Catalog's V3 (same shared trip-code vocabulary,
-- database-per-service). Generous availability so one-way and round-trip bookings can be demoed freely.
INSERT INTO trip_seats (trip_code, total_seats, available) VALUES
    ('IT8001-20260706', 150, 150),
    ('IT8002-20260706', 150, 150),
    ('FR9700-20260706', 150, 150),
    ('FR9701-20260706', 150, 150),
    ('FR9800-20260706', 150, 150),
    ('FR9801-20260706', 150, 150);
