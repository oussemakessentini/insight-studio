import { useEffect } from 'react'
import { navigate } from '../lib/router'

/** Replaces the current address with `to` (no extra history entry), e.g. to the sign-in page. */
export function Redirect({ to }: { to: string }) {
  useEffect(() => navigate(to, { replace: true }), [to])
  return null
}
