import { signInHref } from '../lib/router'
import { LockIcon } from './Icons'
import { Link } from './Link'

/** Shown on every page of the public demo: it is read-only and signing in unlocks your own data. */
export function DemoBanner() {
  return (
    <div className="demo-banner" role="note" aria-label="Read-only demo">
      <LockIcon width={16} height={16} />
      <p>
        You're viewing a read-only demo
        <span aria-hidden="true"> · </span>
        <Link href={signInHref()}>Sign in</Link> / <Link href={signInHref(undefined, '/sign-up')}>Create account</Link>
      </p>
    </div>
  )
}
