import { createContext, useContext } from 'react'
import type { Membership, Session } from '../api/account'

export interface SessionContextValue {
  session: Session
  /**
   * Re-reads GET /api/session, e.g. after signing in or out, or creating or leaving a business.
   * `thenGo` picks where to go with the new session; the address and the session change in the
   * same render, so no page sees one without the other (and redirects don't race).
   */
  reload: (thenGo?: (session: Session) => string | null) => Promise<Session>
  /** The business the signed-in user works on, validated against their memberships; null if none. */
  businessId: number | null
  /**
   * Switches business: remembers the choice and opens `to` (default: its dashboard, with fresh
   * filters since store ids differ per business); `null` stays on the current address.
   */
  selectBusiness: (businessId: number, to?: string | null) => void
}

export const SessionContext = createContext<SessionContextValue | null>(null)

/** The session; null only while the app is still loading it. */
export function useSession(): SessionContextValue | null {
  return useContext(SessionContext)
}

/** The session, for components that only render once it has loaded. */
export function useLoadedSession(): SessionContextValue {
  const value = useContext(SessionContext)
  if (!value) throw new Error('useLoadedSession() needs a loaded session.')
  return value
}

const STORAGE_KEY = 'insight-studio.businessId'

/** The last business chosen in this browser. Only a hint: validated against the memberships. */
export function readStoredBusinessId(): number | null {
  try {
    const value = Number(window.localStorage.getItem(STORAGE_KEY))
    return Number.isInteger(value) && value > 0 ? value : null
  } catch {
    return null // Storage blocked (private mode, site data disabled).
  }
}

export function storeBusinessId(businessId: number): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, String(businessId))
  } catch {
    // Not remembered across reloads; the first membership is used instead.
  }
}

/**
 * The business to work on: the stored choice if the user is still a member, otherwise their first
 * membership. Never trusts a stale id (removed membership, other user on this browser).
 */
export function resolveBusinessId(session: Session, storedId: number | null): number | null {
  if (!session.authenticated || session.memberships.length === 0) return null
  const stored = session.memberships.find((m) => m.businessId === storedId)
  return (stored ?? session.memberships[0]).businessId
}

export function currentMembership(value: SessionContextValue): Membership | null {
  return value.session.memberships.find((m) => m.businessId === value.businessId) ?? null
}
