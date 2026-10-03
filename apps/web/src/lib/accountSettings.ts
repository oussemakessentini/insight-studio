// Pure helpers for the business settings and deletion flows (tests/accountSettings.test.ts).

/** The typed business name confirms deletion: equal to the name after trimming, case included (contract §4). */
export function businessNameConfirmed(typed: string, businessName: string): boolean {
  return typed.trim() !== '' && typed.trim() === businessName.trim()
}

/** The typed email confirms account deletion: equal to the account email, case ignored (contract §4). */
export function emailConfirmed(typed: string, email: string): boolean {
  return typed.trim() !== '' && typed.trim().toLowerCase() === email.trim().toLowerCase()
}

/** Every IANA zone the browser knows, plus the given ones (e.g. the saved zone), sorted. */
export function allTimeZones(...include: string[]): string[] {
  let zones: string[]
  try {
    zones = Intl.supportedValuesOf('timeZone')
  } catch {
    zones = []
  }
  return [...new Set([...zones, 'UTC', ...include.filter(Boolean)])].sort()
}

/** "America/Argentina/Buenos_Aires" → "America / Argentina / Buenos Aires". */
export function timeZoneLabel(zone: string): string {
  return zone.replaceAll('_', ' ').replaceAll('/', ' / ')
}

/**
 * Zones matching a search: every word must appear in the zone (spaces and underscores alike, case
 * ignored), so "new york", "york" and "america/new" all find America/New_York. `keep` (the
 * selected zone) always stays in the list so the select never loses its value.
 */
export function filterTimeZones(zones: string[], query: string, keep?: string): string[] {
  const words = query.toLowerCase().replaceAll('_', ' ').split(/\s+/).filter(Boolean)
  if (words.length === 0) return zones
  const matches = zones.filter((zone) => {
    const haystack = zone.toLowerCase().replaceAll('_', ' ')
    return words.every((word) => haystack.includes(word))
  })
  if (keep && !matches.includes(keep)) return [keep, ...matches]
  return matches
}

/** The fields that changed, trimmed; empty when nothing changed. */
export function settingsChange(
  saved: { name: string; timeZone: string; currency: string },
  edited: { name: string; timeZone: string; currency: string },
): { name?: string; timeZone?: string; currency?: string } {
  const change: { name?: string; timeZone?: string; currency?: string } = {}
  if (edited.name.trim() !== saved.name) change.name = edited.name.trim()
  if (edited.timeZone !== saved.timeZone) change.timeZone = edited.timeZone
  if (edited.currency !== saved.currency) change.currency = edited.currency
  return change
}

/** Same rules as the server (1–200 characters after trimming, no control characters). */
export function businessNameError(name: string): string | null {
  const value = name.trim()
  if (!value) return 'Enter the business name.'
  if (value.length > 200) return 'Use at most 200 characters.'
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(value)) return "The name can't contain control characters."
  return null
}

const FALLBACK_CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'AUD', 'CHF', 'JPY', 'TND', 'MAD']

/** ISO 4217 currencies the browser knows, labelled "EUR · Euro". */
export function currencyOptions(): { code: string; label: string }[] {
  let codes: string[]
  try {
    codes = Intl.supportedValuesOf('currency')
  } catch {
    codes = FALLBACK_CURRENCIES
  }
  let names: Intl.DisplayNames | null = null
  try {
    names = new Intl.DisplayNames(['en'], { type: 'currency' })
  } catch {
    // Codes only.
  }
  return codes.map((code) => {
    const name = names?.of(code)
    return { code, label: name && name !== code ? `${code} · ${name}` : code }
  })
}
