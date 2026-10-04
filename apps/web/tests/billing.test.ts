import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { Subscription } from '../src/api/billing.ts'
import {
  CHECKOUT_POLL_TIMEOUT_MS,
  checkoutPollDecision,
  checkoutReturnOf,
  limitKey,
  OVER_LIMIT_RULE,
  periodSentence,
  planLimitOf,
  showsSeePlans,
  statusBadge,
  usageLabel,
  usageNote,
  usagePercent,
  usageState,
  usageText,
} from '../src/lib/billing.ts'

const ZONE = 'America/New_York'

function sub(status: Subscription['status'], extra: Partial<Subscription> = {}): Subscription {
  return { status, cancelAtPeriodEnd: false, currentPeriodEnd: '2026-11-03T04:30:00Z', canceledAt: null, ...extra }
}

describe('statusBadge', () => {
  it('shows nothing without a subscription', () => {
    assert.equal(statusBadge(null, ZONE), null)
    assert.equal(statusBadge(sub('none'), ZONE), null)
  })

  it('labels the contract statuses', () => {
    assert.deepEqual(statusBadge(sub('active'), ZONE), { label: 'Active', tone: 'good' })
    assert.deepEqual(statusBadge(sub('past_due'), ZONE), { label: 'Payment failed', tone: 'bad' })
    assert.deepEqual(statusBadge(sub('unpaid'), ZONE), { label: 'Payment failed', tone: 'bad' })
    assert.deepEqual(statusBadge(sub('canceled'), ZONE), { label: 'Canceled', tone: 'neutral' })
    assert.equal(statusBadge(sub('incomplete'), ZONE)?.label, 'Payment incomplete')
  })

  it('dates a cancellation at period end in the business time zone', () => {
    // 04:30 UTC on Nov 3 is still Nov 2 in New York.
    assert.deepEqual(statusBadge(sub('active', { cancelAtPeriodEnd: true }), ZONE), { label: 'Cancels on Nov 2, 2026', tone: 'warn' })
    assert.equal(statusBadge(sub('active', { cancelAtPeriodEnd: true }), 'Europe/Paris')?.label, 'Cancels on Nov 3, 2026')
  })
})

describe('periodSentence', () => {
  it('describes renewal, cancellation and grace', () => {
    assert.equal(periodSentence(sub('active'), ZONE), 'Renews on Nov 2, 2026.')
    assert.equal(periodSentence(sub('active', { cancelAtPeriodEnd: true }), ZONE), 'Pro until Nov 2, 2026, then Free. Nothing is deleted.')
    assert.match(periodSentence(sub('past_due'), ZONE) ?? '', /Still on Pro while the payment is retried/)
    assert.equal(periodSentence(sub('canceled', { canceledAt: '2026-10-01T12:00:00Z' }), ZONE), 'Canceled on Oct 1, 2026.')
    assert.equal(periodSentence(null, ZONE), null)
  })
})

describe('usage', () => {
  it('formats used of limit with a noun agreeing with the limit', () => {
    assert.equal(usageText({ resource: 'stores', used: 2, limit: 3 }), '2 of 3 stores')
    assert.equal(usageText({ resource: 'members', used: 1, limit: 1 }), '1 of 1 member')
    assert.equal(usageText({ resource: 'imports-per-month', used: 11, limit: 10 }), '11 of 10 imports this month')
    assert.equal(usageLabel('imports-per-month'), 'Imports this month')
    assert.equal(usageLabel('dashboards'), 'Dashboards')
  })

  it('maps resources to plan limit keys', () => {
    assert.equal(limitKey('imports-per-month'), 'importsPerMonth')
    assert.equal(limitKey('stores'), 'stores')
    assert.equal(limitKey('widgets'), null)
  })

  it('classifies and fills meters', () => {
    assert.equal(usageState({ resource: 'stores', used: 1, limit: 3 }), 'ok')
    assert.equal(usageState({ resource: 'charts', used: 8, limit: 10 }), 'near')
    assert.equal(usageState({ resource: 'stores', used: 2, limit: 2 }), 'full')
    assert.equal(usageState({ resource: 'stores', used: 5, limit: 2 }), 'over')
    assert.equal(usagePercent({ resource: 'stores', used: 1, limit: 4 }), 25)
    assert.equal(usagePercent({ resource: 'stores', used: 5, limit: 2 }), 100)
    assert.equal(usagePercent({ resource: 'stores', used: 0, limit: 0 }), 0)
  })

  it('explains full and over-limit meters with the downgrade rule', () => {
    assert.equal(usageNote({ resource: 'stores', used: 1, limit: 2 }), null)
    assert.equal(usageNote({ resource: 'stores', used: 5, limit: 2 }), `Over the limit by 3. ${OVER_LIMIT_RULE}`)
    assert.equal(OVER_LIMIT_RULE, "Existing items are kept; you can't add more until you're under the limit.")
    assert.equal(usageNote({ resource: 'stores', used: 2, limit: 2 }), "Limit reached: you can't add more stores.")
    assert.match(usageNote({ resource: 'members', used: 3, limit: 3 }) ?? '', /invitations/)
  })
})

describe('plan-limit errors', () => {
  const limitError = {
    status: 409,
    message: 'The Free plan allows 2 stores. Upgrade to Pro or delete one first.',
    problem: { code: 'plan_limit', resource: 'stores', limit: 2, used: 2, plan: 'free', upgradeAvailable: true },
  }

  it('reads the problem body', () => {
    assert.deepEqual(planLimitOf(limitError), { resource: 'stores', limit: 2, used: 2, plan: 'free', upgradeAvailable: true })
  })

  it('ignores other errors', () => {
    assert.equal(planLimitOf(null), null)
    assert.equal(planLimitOf(new Error('boom')), null)
    assert.equal(planLimitOf({ status: 409, problem: { detail: 'Name taken' } }), null)
    assert.equal(planLimitOf({ status: 409, problem: null }), null)
  })

  it('offers "See plans" to owners and admins only', () => {
    assert.equal(showsSeePlans(limitError, 'OWNER'), true)
    assert.equal(showsSeePlans(limitError, 'ADMIN'), true)
    assert.equal(showsSeePlans(limitError, 'VIEWER'), false)
    assert.equal(showsSeePlans(limitError, null), false)
    assert.equal(showsSeePlans(new Error('x'), 'OWNER'), false)
  })
})

describe('checkout return', () => {
  it('reads ?checkout=', () => {
    assert.equal(checkoutReturnOf('?checkout=success'), 'success')
    assert.equal(checkoutReturnOf('?a=1&checkout=canceled'), 'canceled')
    assert.equal(checkoutReturnOf('?checkout=other'), null)
    assert.equal(checkoutReturnOf(''), null)
  })

  it('polls until Pro, for at most 60 seconds', () => {
    const start = 1_000_000
    assert.equal(checkoutPollDecision('free', start, start), 'poll')
    assert.equal(checkoutPollDecision(null, start, start + 58_000), 'poll')
    assert.equal(checkoutPollDecision('pro', start, start + 4_000), 'confirmed')
    assert.equal(checkoutPollDecision('pro', start, start + 90_000), 'confirmed')
    assert.equal(checkoutPollDecision('free', start, start + CHECKOUT_POLL_TIMEOUT_MS), 'timeout')
    assert.equal(CHECKOUT_POLL_TIMEOUT_MS, 60_000)
  })
})
