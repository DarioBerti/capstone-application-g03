import type { LegStatus } from '../../types'

// A leg still part of the live booking — reserved seats or a confirmed purchase. RELEASED / EXPIRED /
// SOLD_OUT legs have dropped out and don't count toward the cart, the round-trip flow, or a discard.
export const ACTIVE_LEG_STATUSES = new Set<LegStatus>(['PENDING', 'RESERVED', 'CONFIRMED'])
