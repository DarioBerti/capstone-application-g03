-- Dedupe the four hub stations that two seed generations inserted twice. V2 seeded demo stations under
-- short sigla codes (TRN/MIL/ROM/NAP); V4 later seeded the SAME physical stations under their RFI numeric
-- codes (8300219 Torino Porta Nuova, 8301700 Milano Centrale, 8308409 Roma Termini, 8309218 Napoli
-- Centrale) -> two rows share each name and the storefront pickers list every hub twice.
--
-- Fix: repoint the V2/V3 demo trips from the sigla codes onto the RFI codes first (trips.origin_code /
-- destination_code are FKs to stations.code), THEN delete the now-unreferenced sigla rows. Trip `code`
-- values are the cross-service vocabulary (Inventory/Orders reference them) and are left untouched.

UPDATE trips SET origin_code = m.rfi
FROM (VALUES ('TRN','8300219'), ('MIL','8301700'), ('ROM','8308409'), ('NAP','8309218')) AS m(sigla, rfi)
WHERE trips.origin_code = m.sigla;

UPDATE trips SET destination_code = m.rfi
FROM (VALUES ('TRN','8300219'), ('MIL','8301700'), ('ROM','8308409'), ('NAP','8309218')) AS m(sigla, rfi)
WHERE trips.destination_code = m.sigla;

DELETE FROM stations s
WHERE s.code IN ('TRN','MIL','ROM','NAP')
  AND NOT EXISTS (SELECT 1 FROM trips t WHERE t.origin_code = s.code OR t.destination_code = s.code);
