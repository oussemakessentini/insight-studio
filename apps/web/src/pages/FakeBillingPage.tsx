import { useState } from 'react'
import {
  FAKE_CHECKOUT_ACTIONS,
  FAKE_PORTAL_ACTIONS,
  fakeBillingApi,
  type FakeAction,
  type FakeSession,
  type FakeSessionKind,
} from '../api/billing'
import { ApiError } from '../api/client'
import { FormError } from '../components/Form'
import { Link } from '../components/Link'
import { ErrorState, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { BILLING_PATH, periodSentence, statusBadge } from '../lib/billing'
import { navigate } from '../lib/router'
import { useLoadedSession } from '../lib/session'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/billing.css'

type Outcome = { tone: 'good' | 'warn' | 'bad'; message: string; done?: boolean } | null

interface ActionSpec {
  action: FakeAction
  label: string
  outcome: Outcome
  primary?: boolean
}

const CHECKOUT_RETURN_SUCCESS = `${BILLING_PATH}?checkout=success`
const CHECKOUT_RETURN_CANCELED = `${BILLING_PATH}?checkout=canceled`

const PORTAL_ACTIONS: ActionSpec[] = [
  {
    action: FAKE_PORTAL_ACTIONS.cancelAtPeriodEnd,
    label: 'Cancel at period end',
    outcome: { tone: 'warn', message: 'The subscription will cancel at the end of the period. It stays Pro until then.' },
  },
  { action: FAKE_PORTAL_ACTIONS.resume, label: 'Resume', outcome: { tone: 'good', message: 'The subscription will renew as usual.' } },
  { action: FAKE_PORTAL_ACTIONS.cancelNow, label: 'Cancel now', outcome: { tone: 'warn', message: 'The subscription is canceled. The business is on Free.' } },
  {
    action: FAKE_PORTAL_ACTIONS.failRenewal,
    label: 'Simulate failed renewal',
    outcome: { tone: 'bad', message: 'The renewal payment failed: the subscription is past due (still Pro while it is retried).' },
  },
  {
    action: FAKE_PORTAL_ACTIONS.unpaid,
    label: 'Simulate unpaid',
    outcome: { tone: 'bad', message: 'The subscription is unpaid. The business is back on Free.' },
  },
  {
    action: FAKE_PORTAL_ACTIONS.payOutstanding,
    label: 'Pay outstanding invoice',
    outcome: { tone: 'good', message: 'The outstanding invoice is paid: the subscription is active.' },
  },
]

/**
 * The fake payment provider's checkout and portal (billing contract §7): local stand-ins for Stripe's
 * pages, clearly labelled as tests. Each button changes the fake's state, which then reaches the
 * business through the same signed webhooks as a real provider.
 */
export function FakeBillingPage({ kind, sessionId }: { kind: FakeSessionKind; sessionId: string }) {
  const { session: appSession, selectBusiness } = useLoadedSession()
  const loaded = useApi(`fake-billing|${sessionId}`, (signal) => fakeBillingApi.get(sessionId, signal))
  const [updated, setUpdated] = useState<FakeSession | null>(null)
  const [busy, setBusy] = useState<FakeAction | null>(null)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const [error, setError] = useState<unknown>(null)
  const fake = updated ?? loaded.data
  const checkout = kind === 'checkout'
  const title = checkout ? 'Test checkout (no real payment)' : 'Test billing portal'
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone

  // Back to the app, in the business this session belongs to when the user is one of its members.
  const goBack = (target: string) => {
    const businessId = fake?.businessId
    if (businessId !== undefined && appSession.memberships.some((m) => m.businessId === businessId)) selectBusiness(businessId, target)
    else navigate(target)
  }

  const act = async (spec: ActionSpec) => {
    setBusy(spec.action)
    setError(null)
    setOutcome(null)
    try {
      const answer = await fakeBillingApi.act(sessionId, spec.action)
      setUpdated(answer && typeof answer === 'object' && 'id' in answer ? answer : await fakeBillingApi.get(sessionId).catch(() => null))
      setOutcome(spec.outcome)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(null)
    }
  }

  const cancelCheckout = async () => {
    setBusy(FAKE_CHECKOUT_ACTIONS.cancel)
    setError(null)
    // Leaving matters more than recording it: an abandoned fake checkout simply expires.
    await fakeBillingApi.act(sessionId, FAKE_CHECKOUT_ACTIONS.cancel).catch(() => undefined)
    goBack(CHECKOUT_RETURN_CANCELED)
  }

  const paid = checkout && (outcome?.done === true || fake?.status === 'completed' || fake?.status === 'complete')

  return (
    <div className="fake-billing">
      <p className="fake-billing-banner" role="note">
        <strong>{checkout ? 'Test checkout — no real payment' : 'Test billing portal — no real payment'}</strong>
        <span>This page is simulated by the local fake payment provider. No card is charged.</span>
      </p>

      <section className="panel fake-billing-card" aria-labelledby="fake-billing-title">
        <h1 id="fake-billing-title" className="auth-title">
          {title}
        </h1>

        {loaded.error && !fake ? (
          <>
            <ErrorState
              message={
                loaded.error instanceof ApiError && loaded.error.status === 404
                  ? 'This test session does not exist or has expired. Start again from Settings › Billing.'
                  : loaded.error.message
              }
              onRetry={loaded.error instanceof ApiError && loaded.error.status === 404 ? undefined : loaded.retry}
            />
            <p>
              <Link className="button button-secondary" href={BILLING_PATH}>
                Back to Insight Studio
              </Link>
            </p>
          </>
        ) : !fake ? (
          <SkeletonRows rows={3} />
        ) : (
          <>
            <FakeSummary fake={fake} checkout={checkout} timeZone={timeZone} />

            <div className="fake-billing-outcome" role="status" aria-live="polite">
              {outcome && <p className={`billing-callout tone-${outcome.tone}`}>{outcome.message}</p>}
            </div>
            <div aria-live="polite">{error != null && <FormError>{errorMessage(error)}</FormError>}</div>

            {checkout ? (
              paid ? (
                <div className="fake-billing-actions">
                  <button type="button" className="button button-primary" onClick={() => goBack(CHECKOUT_RETURN_SUCCESS)}>
                    Return to Insight Studio
                  </button>
                </div>
              ) : (
                <div className="fake-billing-actions">
                  <button
                    type="button"
                    className="button button-primary"
                    disabled={busy !== null}
                    aria-busy={busy === FAKE_CHECKOUT_ACTIONS.pay}
                    onClick={() =>
                      void act({
                        action: FAKE_CHECKOUT_ACTIONS.pay,
                        label: 'Pay with test card',
                        outcome: { tone: 'good', message: 'Payment succeeded. The subscription is active.', done: true },
                      })
                    }
                  >
                    {busy === FAKE_CHECKOUT_ACTIONS.pay ? 'Paying…' : 'Pay with test card'}
                  </button>
                  <button
                    type="button"
                    className="button button-secondary"
                    disabled={busy !== null}
                    aria-busy={busy === FAKE_CHECKOUT_ACTIONS.decline}
                    onClick={() =>
                      void act({
                        action: FAKE_CHECKOUT_ACTIONS.decline,
                        label: 'Card declined',
                        outcome: {
                          tone: 'bad',
                          message: 'The card was declined. The business is still on Free. You can try Pay with test card again.',
                        },
                      })
                    }
                  >
                    Card declined
                  </button>
                  <button type="button" className="button button-secondary" disabled={busy !== null} onClick={() => void cancelCheckout()}>
                    Cancel
                  </button>
                </div>
              )
            ) : (
              <>
                <div className="fake-billing-actions fake-billing-portal-actions">
                  {PORTAL_ACTIONS.map((spec) => (
                    <button
                      key={spec.action}
                      type="button"
                      className="button button-secondary"
                      disabled={busy !== null}
                      aria-busy={busy === spec.action}
                      onClick={() => void act(spec)}
                    >
                      {spec.label}
                    </button>
                  ))}
                </div>
                <p className="form-hint">Changes reach Insight Studio through webhooks, usually within a few seconds.</p>
                <div className="fake-billing-actions">
                  <button type="button" className="button button-primary" onClick={() => goBack(BILLING_PATH)}>
                    Back to Insight Studio
                  </button>
                </div>
              </>
            )}
          </>
        )}
      </section>
    </div>
  )
}

function FakeSummary({ fake, checkout, timeZone }: { fake: FakeSession; checkout: boolean; timeZone: string }) {
  const badge = statusBadge(fake.subscription ?? null, timeZone)
  const period = periodSentence(fake.subscription ?? null, timeZone)
  return (
    <dl className="details-list fake-billing-details">
      {fake.businessName && (
        <div>
          <dt>Business</dt>
          <dd className="break-anywhere">{fake.businessName}</dd>
        </div>
      )}
      {fake.plan && (
        <div>
          <dt>{checkout ? 'Plan' : 'Subscription'}</dt>
          <dd>
            {fake.plan.name} · {fake.plan.priceDisplay}
          </dd>
        </div>
      )}
      {!checkout && (
        <div>
          <dt>Status</dt>
          <dd>
            {badge ? <span className={`billing-badge tone-${badge.tone}`}>{badge.label}</span> : 'No subscription'}
            {period && <span className="fake-billing-period"> {period}</span>}
          </dd>
        </div>
      )}
    </dl>
  )
}
