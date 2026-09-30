// Accounts, businesses, members and catalog setup (contract docs/accounts-contract.md §5).
import { deleteJson, getJson, patchJson, postJson } from './client'
import type { ProductInfo, Role } from './types'

export interface SessionUser {
  id: number
  email: string
  displayName: string
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

  signUp: (email: string, password: string, displayName: string) =>
    postJson<SignInResponse>('/api/auth/sign-up', { email, password, displayName }, credentials),

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

  add: (businessId: number, email: string, role: Role) => postJson<Member>(members(businessId), { email, role }, { businessId }),

  changeRole: (businessId: number, userId: number, role: Role) =>
    patchJson<Member>(`${members(businessId)}/${userId}`, { role }, { businessId }),

  remove: (businessId: number, userId: number) => deleteJson(`${members(businessId)}/${userId}`, { businessId }),
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
