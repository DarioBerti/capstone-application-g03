-- PayPal (sandbox) integration: keep the PSP capture id alongside the authorization id (psp_reference) so a
-- captured payment can be reconciled against the PayPal dashboard. Nullable — set only on the CAPTURED
-- transition; existing AUTHORIZED/DECLINED/VOIDED rows keep it NULL.
ALTER TABLE payment_authorizations
    ADD COLUMN capture_reference VARCHAR(64);
