// Subscription billing (docs/billing-contract.md §6, §7).
import { getJson, postJson } from './client'

export type PlanKey = 'free' | 'pro' | (string & {})

export interface PlanLimits {
  members: number
  stores: number
  charts: number
  dashboards: number
  importsPerMonth: number
}

export interface Plan {
  key: PlanKey
  name: string
  /** Shown as is, e.g. "$29 / month" or "Free". */
  priceDisplay: string
  limits: PlanLimits
}

/** Provider statuses (Stripe's vocabulary); the effective plan follows from plan + status (§1). */
export type SubscriptionStatus =
  | 'none'
  | 'incomplete'
  | 'incomplete_expired'
  | 'trialing'
  | 'active'
  | 'past_due'
  | 'unpaid'
  | 'canceled'
  | 'paused'

export interface Subscription {
  status: SubscriptionStatus
  cancelAtPeriodEnd: boolean
  currentPeriodEnd: string | null
  canceledAt: string | null
}

/** `members`, `stores`, `charts`, `dashboards`, `imports-per-month` (contract §1). */
export type UsageResource = 'members' | 'stores' | 'charts' | 'dashboards' | 'imports-per-month' | (string & {})

export interface Usage {
  resource: UsageResource
  used: number
  limit: number
}

export type BillingProviderName = 'fake' | 'stripe' | 'none'

export interface Billing {
  /** The effective plan. */
  plan: Plan
  subscription: Subscription | null
  paymentProblem: boolean
  provider: BillingProviderName
  /** The caller is an OWNER of the business. */
  canManage: boolean
  usage: Usage[]
  plans: Plan[]
}

const business = (businessId: number) => `/api/businesses/${businessId}/billing`

export const billingApi = {
  /** ADMIN+. `404` when billing is off (provider `none`) or for the public demo. */
  get: (businessId: number, signal?: AbortSignal) => getJson<Billing>(business(businessId), {}, signal, { businessId }),

  /** OWNER, verified. `409` when already on Pro (use the portal), `503` when the provider is unavailable. */
  checkout: (businessId: number, plan: PlanKey = 'pro') =>
    postJson<{ url: string }>(`${business(businessId)}/checkout`, { plan }, { businessId }),

  /** OWNER, verified. `409` when the business has no billing account yet. */
  portal: (businessId: number) => postJson<{ url: string }>(`${business(businessId)}/portal`, undefined, { businessId }),

  plans: (signal?: AbortSignal) => getJson<Plan[]>('/api/billing/plans', {}, signal, { businessId: null }),
}

// ---- Fake provider (§7, development and tests only) --------------------------------------------

export type FakeSessionKind = 'checkout' | 'portal'

/**
 * A fake checkout or portal session. The contract leaves its exact shape to the backend; every field
 * except the id is optional here and the pages show what they get.
 */
export interface FakeSession {
  id: string
  kind?: FakeSessionKind
  businessId?: number
  businessName?: string
  /** The plan being bought (checkout) or subscribed to (portal). */
  plan?: Pick<Plan, 'key' | 'name' | 'priceDisplay'> | null
  /** The session's own state, e.g. `open`, `completed`, `declined`, `canceled`. */
  status?: string
  subscription?: Subscription | null
}

/** Actions of `POST /api/billing/fake/{sessionId}/{action}`, named after the §7 buttons. */
export const FAKE_CHECKOUT_ACTIONS = {
  pay: 'pay',
  decline: 'decline',
  cancel: 'cancel',
} as const

export const FAKE_PORTAL_ACTIONS = {
  cancelAtPeriodEnd: 'cancel-at-period-end',
  resume: 'resume',
  cancelNow: 'cancel-now',
  failRenewal: 'fail-renewal',
  unpaid: 'unpaid',
  payOutstanding: 'pay-outstanding',
} as const

export type FakeAction =
  | (typeof FAKE_CHECKOUT_ACTIONS)[keyof typeof FAKE_CHECKOUT_ACTIONS]
  | (typeof FAKE_PORTAL_ACTIONS)[keyof typeof FAKE_PORTAL_ACTIONS]

// The session id selects the business on the server; no X-Business-Id is sent.
const fake = (sessionId: string) => `/api/billing/fake/${encodeURIComponent(sessionId)}`

export const fakeBillingApi = {
  get: (sessionId: string, signal?: AbortSignal) => getJson<FakeSession>(fake(sessionId), {}, signal, { businessId: null }),

  /** Resolves to the updated session when the API answers with one (undefined for an empty answer). */
  act: (sessionId: string, action: FakeAction) =>
    postJson<FakeSession | undefined>(`${fake(sessionId)}/${action}`, undefined, { businessId: null }),
}
