// Accounts, businesses, members and catalog setup (contract docs/accounts-contract.md §5).
import { deleteJson, getJson, patchJson, postJson } from './client'
import type { ProductInfo, Role } from './types'

export interface SessionUser {
  id: number
  email: string
  displayName: string
  /** Until verified, the account can read but not create or change a business. */
  emailVerified: boolean
}

export interface Membership {
  businessId: number
  name: string
  slug: string
  role: Role
}

export interface DemoInfo {
  enabled: boolean
  businessId: number
  name: string
}

export interface Session {
  authenticated: boolean
  user: SessionUser | null
  memberships: Membership[]
  demo: DemoInfo | null
}

export interface SignInResponse {
  user: SessionUser
  memberships: Membership[]
}

export interface Business {
  businessId: number
  name: string
  slug: string
  currency: string
  timeZone: string
  role: Role
}

export interface Member {
  userId: number
  email: string
  displayName: string
  role: Role
  /** When the membership was created (ISO instant). */
  since: string
}

/** An open invitation, as owners and admins see it. It never says whether the address has an account. */
export interface Invitation {
  id: number
  email: string
  role: Role
  invitedBy: string
  createdAt: string
  expiresAt: string
}

/** What an invitation link offers (shown before accepting; no sign-in needed). */
export interface InvitationPreview {
  businessName: string
  role: Role
  invitedBy: string
  /** Only the account with this email can accept. */
  email: string
  expiresAt: string
}

export interface CreatedStore {
  id: number
  code: string
  name: string
  city: string | null
}

/**
 * Mirrors the server policy (account/PasswordPolicy): at least 12 characters and at most 72 UTF-8
 * bytes, bcrypt's input limit. The server stays authoritative.
 */
export const PASSWORD_POLICY = { minLength: 12, maxBytes: 72 }

// None of these are about a business chosen by the X-Business-Id header.
const account = { businessId: null }
// Sign-in answers 401 for a wrong password; that is not an ended session.
const credentials = { businessId: null, expectUnauthorized: true }

export const sessionApi = {
  get: (signal?: AbortSignal) => getJson<Session>('/api/session', {}, signal, credentials),
}

export const authApi = {
  signIn: (email: string, password: string) =>
    postJson<SignInResponse>('/api/auth/sign-in', { email, password }, credentials),

  // Always 202 once the input is valid (the same answer for an address that already has an
  // account); nobody is signed in.
  signUp: (email: string, password: string, displayName: string) =>
    postJson('/api/auth/sign-up', { email, password, displayName }, credentials),

  verifyEmail: (token: string) => postJson('/api/auth/verify-email', { token }, credentials),

  resendVerification: () => postJson('/api/auth/verify-email/resend', undefined, account),

  signOut: () => postJson('/api/auth/sign-out', undefined, credentials),

  forgotPassword: (email: string) => postJson('/api/auth/password/forgot', { email }, credentials),

  resetPassword: (token: string, newPassword: string) =>
    postJson('/api/auth/password/reset', { token, newPassword }, credentials),

  changePassword: (currentPassword: string, newPassword: string) =>
    // A wrong current password is a validation error, not a reason to sign the user out.
    postJson('/api/auth/password/change', { currentPassword, newPassword }, credentials),
}

export const businessesApi = {
  list: (signal?: AbortSignal) => getJson<Business[]>('/api/businesses', {}, signal, account),

  create: (name: string, currency: string, timeZone: string) =>
    postJson<Business>('/api/businesses', { name, currency, timeZone }, account),
}

// Path ids are checked against the business resolved from X-Business-Id, so each call selects the
// business it names.
const members = (businessId: number) => `/api/businesses/${businessId}/members`

export const membersApi = {
  list: (businessId: number, signal?: AbortSignal) => getJson<Member[]>(members(businessId), {}, signal, { businessId }),

  changeRole: (businessId: number, userId: number, role: Role) =>
    patchJson<Member>(`${members(businessId)}/${userId}`, { role }, { businessId }),

  remove: (businessId: number, userId: number) => deleteJson(`${members(businessId)}/${userId}`, { businessId }),
}

const invitations = (businessId: number) => `/api/businesses/${businessId}/invitations`

export const invitationsApi = {
  list: (businessId: number, signal?: AbortSignal) =>
    getJson<Invitation[]>(invitations(businessId), {}, signal, { businessId }),

  invite: (businessId: number, email: string, role: Role) =>
    postJson<Invitation>(invitations(businessId), { email, role }, { businessId }),

  revoke: (businessId: number, invitationId: number) =>
    deleteJson(`${invitations(businessId)}/${invitationId}`, { businessId }),

  // Tokens go in request bodies, never in API URLs. Previewing works signed out.
  preview: (token: string) => postJson<InvitationPreview>('/api/invitations/preview', { token }, credentials),

  accept: (token: string) => postJson<Business>('/api/invitations/accept', { token }, account),
}

export const catalogApi = {
  createStore: (store: { code: string; name: string; city: string | null }) => postJson<CreatedStore>('/api/stores', store),

  createProduct: (product: { sku: string; name: string; category: string; listPrice: number }) =>
    postJson<ProductInfo>('/api/products', product),
}

export const ROLE_LABELS: Record<Role | 'DEMO', string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  VIEWER: 'Viewer',
  DEMO: 'Demo',
}
