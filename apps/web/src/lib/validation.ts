import { ApiError } from '../api/client'
import { PASSWORD_POLICY } from '../api/account'

// Client-side checks mirror the server policy so mistakes show while typing; the server still
// validates everything.

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function emailError(email: string): string | null {
  const value = email.trim()
  if (!value) return 'Enter an email address.'
  if (value.length > 254 || !EMAIL.test(value)) return 'Enter a valid email address, like name@example.com.'
  return null
}

const utf8 = new TextEncoder()

/**
 * New passwords: at least 12 characters, at most 72 UTF-8 bytes (bcrypt only uses the first 72
 * bytes, so the server refuses longer input rather than truncating it), and not the email address.
 */
export function newPasswordError(password: string, email?: string): string | null {
  if (!password) return 'Enter a password.'
  if (password.length < PASSWORD_POLICY.minLength) return `Use at least ${PASSWORD_POLICY.minLength} characters.`
  if (utf8.encode(password).length > PASSWORD_POLICY.maxBytes) {
    return `Use at most ${PASSWORD_POLICY.maxBytes} characters (fewer if you use accents or emoji).`
  }
  if (email && password.trim().toLowerCase() === email.trim().toLowerCase()) {
    return "Your password can't be your email address."
  }
  return null
}

export function confirmationError(password: string, confirmation: string): string | null {
  if (!confirmation) return 'Enter the password again.'
  return confirmation === password ? null : "The passwords don't match."
}

export function requiredError(value: string, message: string): string | null {
  return value.trim() ? null : message
}

export const PASSWORD_HINT = `${PASSWORD_POLICY.minLength} to ${PASSWORD_POLICY.maxBytes} characters. A few unrelated words make a strong, memorable password.`

/** A message for a failed request, with a wait time for rate limiting (429). */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429 && error.retryAfterSeconds) {
      const minutes = Math.ceil(error.retryAfterSeconds / 60)
      return `Too many attempts. Try again in ${minutes} ${minutes === 1 ? 'minute' : 'minutes'}.`
    }
    return error.message
  }
  return error instanceof Error ? error.message : String(error)
}
