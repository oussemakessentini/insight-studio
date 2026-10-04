import { BILLING_PATH, showsSeePlans } from '../lib/billing'
import { currentMembership, useSession } from '../lib/session'
import { Link } from './Link'

/**
 * "See plans" after a plan-limit error (contract §9), for owners and admins of the current business
 * (the roles that can open Settings › Billing). Renders nothing for any other error or role.
 */
export function SeePlansLink({ error }: { error: unknown }) {
  const session = useSession()
  const role = session ? currentMembership(session)?.role : null
  if (!showsSeePlans(error, role)) return null
  return (
    <>
      {' '}
      <Link className="see-plans-link" href={BILLING_PATH}>
        See plans
      </Link>
    </>
  )
}
