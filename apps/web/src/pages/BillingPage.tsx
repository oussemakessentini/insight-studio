import { useEffect, useEffectEvent, useId, useState } from 'react'
import { billingApi, type Billing, type Plan, type Usage } from '../api/billing'
import { ApiError } from '../api/client'
import { FormError } from '../components/Form'
import { PageHeader } from '../components/PageHeader'
import { ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import {
  CHECKOUT_POLL_INTERVAL_MS,
  checkoutPollDecision,
  checkoutReturnOf,
  LIMIT_ROWS,
  limitKey,
  periodSentence,
  statusBadge,
  usageLabel,
  usageNote,
  usagePercent,
  usageState,
  usageText,
  type CheckoutReturn,
  type StatusBadge,
} from '../lib/billing'
import { formatNumber } from '../lib/format'
import { updateQuery } from '../lib/router'
import { useLoadedSession } from '../lib/session'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/settings.css'
import '../styles/billing.css'
import type { PageProps } from './types'

/**
 * Settings › Billing (billing contract §9): the business's plan and status, usage against the plan's
 * limits and the Free vs Pro comparison. Owners upgrade (checkout) and manage the subscription
 * (portal) at the provider; admins read.
 */
export function BillingPage({ context }: PageProps) {
  const { businessId } = useLoadedSession()
  const timeZone = context.business.timeZone
  const [version, setVersion] = useState(0)
  const billing = useApi(`billing|${businessId}|${version}`, (signal) => billingApi.get(businessId!, signal))

  // Back from the provider (?checkout=success|canceled): read once, then drop it from the address so
  // a refresh doesn't show the message (or poll) again.
  const [checkoutReturn] = useState<CheckoutReturn | null>(() => checkoutReturnOf(window.location.search))
  useEffect(() => {
    if (checkoutReturn) updateQuery({ checkout: null })
  }, [checkoutReturn])
  const confirmation = useCheckoutConfirmation(checkoutReturn === 'success' ? businessId : null, () => setVersion((v) => v + 1))

  const off = billing.error instanceof ApiError && billing.error.status === 404

  return (
    <>
      <PageHeader eyebrow="Settings" title="Billing" subtitle={`The plan of ${context.business.name}, its limits and what it uses.`} />

      <div className="billing-main">
        <div className="billing-announcements" role="status" aria-live="polite">
          {confirmation === 'confirming' && (
            <p className="billing-confirming">
              <span className="billing-spinner" aria-hidden="true" />
              Confirming your payment…
            </p>
          )}
          {confirmation === 'confirmed' && <p className="billing-callout tone-good">You're on Pro. The Pro limits apply now.</p>}
          {confirmation === 'timeout' && (
            <p className="billing-callout tone-warn">
              Still confirming your payment — this can take a few minutes. Refresh this page later.
            </p>
          )}
          {checkoutReturn === 'canceled' && <p className="billing-callout tone-neutral">Checkout canceled. You're still on Free.</p>}
        </div>

        {off ? (
          <BillingOff />
        ) : billing.error ? (
          <div className="panel page-error">
            <ErrorState message={billing.error.message} onRetry={billing.retry} />
          </div>
        ) : !billing.data ? (
          <BillingSkeleton />
        ) : (
          <div className={`billing-sections ${billing.loading ? 'is-refreshing' : ''}`} aria-busy={billing.loading}>
            <BillingContent billing={billing.data} timeZone={timeZone} emailVerified={context.access.emailVerified} />
          </div>
        )}
      </div>
    </>
  )
}

type Confirmation = 'confirming' | 'confirmed' | 'timeout' | null

/**
 * After a successful checkout, the provider's webhook may arrive a little after the redirect: poll the
 * billing every 2 s until the business is on Pro, for at most 60 s. `businessId` null: no polling.
 */
function useCheckoutConfirmation(businessId: number | null, onConfirmed: () => void): Confirmation {
  const [phase, setPhase] = useState<Confirmation>(businessId === null ? null : 'confirming')
  const confirmed = useEffectEvent(onConfirmed)

  useEffect(() => {
    if (businessId === null) return
    const controller = new AbortController()
    const startedAt = Date.now()
    let timer: ReturnType<typeof setTimeout> | undefined

    const check = async () => {
      let planKey: string | null = null
      try {
        planKey = (await billingApi.get(businessId, controller.signal)).plan.key
      } catch {
        // A failed check counts as "not yet"; the deadline still applies.
      }
      if (controller.signal.aborted) return
      const decision = checkoutPollDecision(planKey, startedAt, Date.now())
      if (decision === 'confirmed') {
        setPhase('confirmed')
        confirmed()
      } else if (decision === 'timeout') {
        setPhase('timeout')
      } else {
        timer = setTimeout(() => void check(), CHECKOUT_POLL_INTERVAL_MS)
      }
    }
    void check()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [businessId])

  return phase
}

function BillingContent({ billing, timeZone, emailVerified }: { billing: Billing; timeZone: string; emailVerified: boolean }) {
  const { businessId } = useLoadedSession()
  const [busy, setBusy] = useState<'checkout' | 'portal' | null>(null)
  const [error, setError] = useState<unknown>(null)

  const enabled = billing.provider !== 'none'
  const pro = billing.plans.find((p) => p.key === 'pro')
  const onFree = billing.plan.key === 'free'
  const canUpgrade = enabled && billing.canManage && onFree && pro !== undefined
  // The portal needs a billing account at the provider: one exists once a checkout was started.
  const canOpenPortal = enabled && billing.canManage && billing.subscription !== null
  const actionsDisabled = busy !== null || !emailVerified

  const open = async (kind: 'checkout' | 'portal') => {
    setBusy(kind)
    setError(null)
    try {
      const { url } = kind === 'checkout' ? await billingApi.checkout(businessId!, 'pro') : await billingApi.portal(businessId!)
      // The provider's page (or the fake provider's page in this app); stay busy while the browser leaves.
      window.location.assign(url)
    } catch (err) {
      setError(err)
      setBusy(null)
    }
  }

  const manageButton = (
    <button
      type="button"
      className="button button-secondary"
      onClick={() => void open('portal')}
      disabled={actionsDisabled}
      aria-busy={busy === 'portal'}
    >
      {busy === 'portal' ? 'Opening billing…' : 'Manage billing'}
    </button>
  )
  const badge = statusBadge(billing.subscription, timeZone)
  const period = periodSentence(billing.subscription, timeZone)

  return (
    <>
      {billing.provider === 'fake' && (
        <p className="billing-callout tone-info billing-test-mode">
          <strong>Test mode — no real payments.</strong> Checkout and the billing portal are simulated by this server.
        </p>
      )}
      {billing.provider === 'none' && <BillingOffNote />}

      {billing.paymentProblem && (
        <div className="billing-payment-problem" role="alert">
          <div>
            <p className="billing-payment-problem-title">Payment failed — update your payment method</p>
            <p className="billing-payment-problem-text">
              {billing.subscription?.status === 'past_due'
                ? 'The business stays on Pro while the payment is retried.'
                : 'The business is back on the Free plan until the payment goes through.'}
              {!billing.canManage && ' Ask an owner to update it.'}
            </p>
          </div>
          {canOpenPortal && manageButton}
        </div>
      )}

      <Panel title="Current plan" className="billing-panel">
        <div className="billing-plan">
          <div className="billing-plan-head">
            <span className="billing-plan-name">{billing.plan.name}</span>
            <PlanBadge label={onFree ? 'Free plan' : `${billing.plan.name} plan`} tone={onFree ? 'neutral' : 'good'} />
            {badge && <PlanBadge {...badge} />}
          </div>
          {billing.plan.priceDisplay !== billing.plan.name && <p className="billing-plan-price">{billing.plan.priceDisplay}</p>}
          {period && <p className="billing-plan-period">{period}</p>}

          {billing.canManage ? (
            <>
              {!emailVerified && enabled && <p className="form-hint">Verify your email address to change the plan.</p>}
              <div aria-live="polite">{error != null && <FormError error={error}>{errorMessage(error)}</FormError>}</div>
              {(canUpgrade || canOpenPortal) && (
                <div className="billing-actions">
                  {canUpgrade && (
                    <button
                      type="button"
                      className="button button-primary"
                      onClick={() => void open('checkout')}
                      disabled={actionsDisabled}
                      aria-busy={busy === 'checkout'}
                    >
                      {busy === 'checkout' ? 'Opening checkout…' : `Upgrade to ${pro!.name}`}
                    </button>
                  )}
                  {canOpenPortal && !billing.paymentProblem && manageButton}
                </div>
              )}
              {canUpgrade && pro && <p className="form-hint">{pro.name} costs {pro.priceDisplay}. You can cancel at any time.</p>}
            </>
          ) : (
            <p className="billing-owner-note">Only owners can change the plan.</p>
          )}
        </div>
      </Panel>

      <Panel title="Usage" subtitle={`Against the limits of the ${billing.plan.name} plan.`} className="billing-panel">
        <UsageMeters usage={billing.usage} />
      </Panel>

      {billing.plans.length > 0 && (
        <Panel title="Compare plans" className="billing-panel">
          <PlanComparison plans={billing.plans} current={billing.plan.key} />
        </Panel>
      )}
    </>
  )
}

function PlanBadge({ label, tone }: StatusBadge) {
  return <span className={`billing-badge tone-${tone}`}>{label}</span>
}

const RESOURCE_ORDER = LIMIT_ROWS.map((r) => r.key as string)

function UsageMeters({ usage }: { usage: Usage[] }) {
  const sorted = [...usage].sort((a, b) => RESOURCE_ORDER.indexOf(limitKey(a.resource) ?? '') - RESOURCE_ORDER.indexOf(limitKey(b.resource) ?? ''))
  if (sorted.length === 0) return <p className="form-hint">No usage to show.</p>
  return (
    <ul className="billing-usage">
      {sorted.map((u) => (
        <UsageMeter key={u.resource} usage={u} />
      ))}
    </ul>
  )
}

function UsageMeter({ usage }: { usage: Usage }) {
  const labelId = useId()
  const noteId = useId()
  const state = usageState(usage)
  const text = usageText(usage)
  const note = usageNote(usage)
  return (
    <li className={`billing-meter is-${state}`}>
      <div className="billing-meter-head">
        <span id={labelId} className="billing-meter-label">
          {usageLabel(usage.resource)}
        </span>
        <span className="billing-meter-value">
          {text}
          {state === 'over' && <span className="billing-badge tone-bad">Over limit</span>}
          {state === 'full' && <span className="billing-badge tone-warn">Limit reached</span>}
        </span>
      </div>
      <div
        className="billing-meter-track"
        role="meter"
        aria-labelledby={labelId}
        aria-describedby={note ? noteId : undefined}
        aria-valuemin={0}
        aria-valuemax={Math.max(usage.limit, 0)}
        aria-valuenow={Math.min(Math.max(usage.used, 0), Math.max(usage.limit, 0))}
        aria-valuetext={text}
      >
        <span className="billing-meter-fill" style={{ width: `${usagePercent(usage)}%` }} />
      </div>
      {note && (
        <p id={noteId} className="billing-meter-note">
          {note}
        </p>
      )}
    </li>
  )
}

function PlanComparison({ plans, current }: { plans: Plan[]; current: string }) {
  return (
    <div className="table-scroll">
      <table className="data-table billing-compare">
        <caption className="visually-hidden">Plan limits compared</caption>
        <thead>
          <tr>
            <th scope="col">Limit</th>
            {plans.map((p) => (
              <th key={p.key} scope="col" className={`num ${p.key === current ? 'is-current' : ''}`}>
                <span className="billing-compare-plan">{p.name}</span>
                <span className="billing-compare-price">{p.priceDisplay}</span>
                {p.key === current && <span className="billing-badge tone-good">Current plan</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {LIMIT_ROWS.map((row) => (
            <tr key={row.key}>
              <th scope="row">{row.label}</th>
              {plans.map((p) => (
                <td key={p.key} className={`num ${p.key === current ? 'is-current' : ''}`}>
                  {formatNumber(p.limits[row.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function BillingOffNote() {
  return (
    <p className="billing-callout tone-neutral billing-off">
      Billing is turned off on this server: every business is on the Free plan and its limits still apply.
    </p>
  )
}

/** Provider `none`: the billing endpoints answer 404 (contract §4). */
function BillingOff() {
  return (
    <Panel title="Billing is off" className="billing-panel">
      <BillingOffNote />
    </Panel>
  )
}

function BillingSkeleton() {
  return (
    <div className="billing-sections" aria-busy="true" aria-label="Loading billing">
      <div className="panel billing-panel billing-skeleton">
        <Skeleton height={22} width={160} />
        <Skeleton height={14} width={220} />
      </div>
      <div className="panel billing-panel billing-skeleton">
        <SkeletonRows rows={5} />
      </div>
    </div>
  )
}
