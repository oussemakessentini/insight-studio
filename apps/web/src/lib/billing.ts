// Pure helpers for the Billing page, the fake provider pages and plan-limit errors
// (docs/billing-contract.md). No runtime imports, so node tests load it directly (tests/billing.test.ts).
import type { PlanLimits, Subscription, Usage } from '../api/billing'

export const BILLING_PATH = '/settings/billing'

// ---- Status --------------------------------------------------------------------------------------

export type Tone = 'good' | 'warn' | 'bad' | 'neutral'

export interface StatusBadge {
  label: string
  tone: Tone
}

const dateFormatters = new Map<string, Intl.DateTimeFormat>()

/** "Nov 3, 2026": the calendar date of an instant in the business's time zone. */
export function formatDateInZone(instant: string, timeZone: string): string {
  let formatter = dateFormatters.get(timeZone)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone })
    dateFormatters.set(timeZone, formatter)
  }
  return formatter.format(new Date(instant))
}

/**
 * The subscription's status badge (contract §9: Active, Cancels on {date}, Payment failed, Canceled),
 * or null when the business never subscribed.
 */
export function statusBadge(subscription: Subscription | null, timeZone: string): StatusBadge | null {
  if (!subscription || subscription.status === 'none') return null
  const end = subscription.currentPeriodEnd
  switch (subscription.status) {
    case 'active':
    case 'trialing':
      if (subscription.cancelAtPeriodEnd) {
        return { label: end ? `Cancels on ${formatDateInZone(end, timeZone)}` : 'Cancels at period end', tone: 'warn' }
      }
      return { label: subscription.status === 'trialing' ? 'Trial' : 'Active', tone: 'good' }
    case 'past_due':
    case 'unpaid':
      return { label: 'Payment failed', tone: 'bad' }
    case 'incomplete':
      return { label: 'Payment incomplete', tone: 'warn' }
    case 'incomplete_expired':
      return { label: 'Checkout expired', tone: 'neutral' }
    case 'paused':
      return { label: 'Paused', tone: 'neutral' }
    case 'canceled':
      return { label: 'Canceled', tone: 'neutral' }
    default:
      return { label: String(subscription.status), tone: 'neutral' }
  }
}

/** The renewal or cancellation line under the plan, or null when there is nothing to say. */
export function periodSentence(subscription: Subscription | null, timeZone: string): string | null {
  if (!subscription) return null
  const end = subscription.currentPeriodEnd ? formatDateInZone(subscription.currentPeriodEnd, timeZone) : null
  switch (subscription.status) {
    case 'active':
    case 'trialing':
      if (!end) return null
      return subscription.cancelAtPeriodEnd ? `Pro until ${end}, then Free. Nothing is deleted.` : `Renews on ${end}.`
    case 'past_due':
      return end
        ? `Still on Pro while the payment is retried (period ends ${end}).`
        : 'Still on Pro while the payment is retried.'
    case 'canceled':
      return subscription.canceledAt ? `Canceled on ${formatDateInZone(subscription.canceledAt, timeZone)}.` : null
    default:
      return null
  }
}

// ---- Usage ---------------------------------------------------------------------------------------

const RESOURCE_NOUNS: Record<string, [singular: string, plural: string, suffix?: string]> = {
  members: ['member', 'members'],
  stores: ['store', 'stores'],
  charts: ['chart', 'charts'],
  dashboards: ['dashboard', 'dashboards'],
  'imports-per-month': ['import', 'imports', 'this month'],
}

/** The `limits` key of a usage resource (`imports-per-month` → `importsPerMonth`). */
export function limitKey(resource: string): keyof PlanLimits | null {
  const key = resource === 'imports-per-month' || resource === 'imports' ? 'importsPerMonth' : resource
  return key === 'members' || key === 'stores' || key === 'charts' || key === 'dashboards' || key === 'importsPerMonth' ? key : null
}

function nouns(resource: string): [string, string, string | undefined] {
  const normalized = limitKey(resource) === 'importsPerMonth' ? 'imports-per-month' : resource
  const known = RESOURCE_NOUNS[normalized]
  return known ? [known[0], known[1], known[2]] : [resource, resource, undefined]
}

/** The meter's label: "Stores", "Imports this month". */
export function usageLabel(resource: string): string {
  const [, plural, suffix] = nouns(resource)
  const text = suffix ? `${plural} ${suffix}` : plural
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** "2 of 3 stores", "11 of 10 imports this month" (the noun agrees with the limit). */
export function usageText({ resource, used, limit }: Usage): string {
  const [singular, plural, suffix] = nouns(resource)
  const noun = limit === 1 ? singular : plural
  return `${used} of ${limit} ${noun}${suffix ? ` ${suffix}` : ''}`
}

export type UsageState = 'ok' | 'near' | 'full' | 'over'

/** `over`: above the limit (after a downgrade); `full`: at it; `near`: 80 % or more. */
export function usageState({ used, limit }: Usage): UsageState {
  if (used > limit) return 'over'
  if (used === limit) return 'full'
  if (limit > 0 && used / limit >= 0.8) return 'near'
  return 'ok'
}

/** The filled share of the meter, 0–100 (a full bar when over the limit). */
export function usagePercent({ used, limit }: Usage): number {
  if (limit <= 0) return used > 0 ? 100 : 0
  return Math.min(100, Math.max(0, Math.round((used / limit) * 100)))
}

export const OVER_LIMIT_RULE = "Existing items are kept; you can't add more until you're under the limit."

/** What a full or over-limit meter means (contract §3), or null when there is room left. */
export function usageNote(usage: Usage): string | null {
  const state = usageState(usage)
  const [, plural] = nouns(usage.resource)
  if (state === 'over') return `Over the limit by ${usage.used - usage.limit}. ${OVER_LIMIT_RULE}`
  if (state === 'full') {
    if (limitKey(usage.resource) === 'importsPerMonth') return 'Limit reached: new imports are possible again next month.'
    if (limitKey(usage.resource) === 'members') return 'Limit reached: open invitations also take a seat.'
    return `Limit reached: you can't add more ${plural}.`
  }
  return null
}

/** Rows of the Free vs Pro comparison, in the contract's order. */
export const LIMIT_ROWS: { key: keyof PlanLimits; label: string }[] = [
  { key: 'members', label: 'Members (incl. open invitations)' },
  { key: 'stores', label: 'Stores' },
  { key: 'charts', label: 'Saved charts' },
  { key: 'dashboards', label: 'Dashboards' },
  { key: 'importsPerMonth', label: 'Imports per month' },
]

// ---- Plan-limit errors ---------------------------------------------------------------------------

export interface PlanLimitProblem {
  resource: string | null
  limit: number | null
  used: number | null
  plan: string | null
  upgradeAvailable: boolean
}

/**
 * The plan-limit details of an API error (`409 {code: "plan_limit", …}`, contract §2), or null for
 * any other error. Reads the problem body that `ApiError.problem` carries.
 */
export function planLimitOf(error: unknown): PlanLimitProblem | null {
  if (!error || typeof error !== 'object' || !('problem' in error)) return null
  const problem = (error as { problem: unknown }).problem
  if (!problem || typeof problem !== 'object') return null
  const body = problem as Record<string, unknown>
  if (body.code !== 'plan_limit') return null
  const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null)
  return {
    resource: typeof body.resource === 'string' ? body.resource : null,
    limit: num(body.limit),
    used: num(body.used),
    plan: typeof body.plan === 'string' ? body.plan : null,
    upgradeAvailable: body.upgradeAvailable !== false,
  }
}

/** Plan-limit errors get a "See plans" link for the roles that can open the Billing page. */
export function showsSeePlans(error: unknown, role: string | null | undefined): boolean {
  return planLimitOf(error) !== null && (role === 'OWNER' || role === 'ADMIN')
}

// ---- Returning from checkout ---------------------------------------------------------------------

export type CheckoutReturn = 'success' | 'canceled'

/** `?checkout=success|canceled` (the provider's return URLs, contract §6), or null. */
export function checkoutReturnOf(search: string): CheckoutReturn | null {
  const value = new URLSearchParams(search).get('checkout')
  return value === 'success' || value === 'canceled' ? value : null
}

export const CHECKOUT_POLL_INTERVAL_MS = 2_000
export const CHECKOUT_POLL_TIMEOUT_MS = 60_000

export type CheckoutPollDecision = 'confirmed' | 'poll' | 'timeout'

/**
 * After a successful checkout the webhook may land a little later: poll every 2 s until the
 * business is on a paid plan, for at most 60 s (contract §9).
 */
export function checkoutPollDecision(planKey: string | null | undefined, startedAt: number, now: number): CheckoutPollDecision {
  if (planKey && planKey !== 'free') return 'confirmed'
  return now - startedAt >= CHECKOUT_POLL_TIMEOUT_MS ? 'timeout' : 'poll'
}
