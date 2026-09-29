import type { Granularity } from '../api/types'

const locale = 'en-US'

const currencyFormatters = new Map<string, Intl.NumberFormat>()

export function formatCurrency(value: number, currency: string, options: { compact?: boolean } = {}): string {
  const key = `${currency}-${options.compact ? 'c' : 'f'}`
  let formatter = currencyFormatters.get(key)
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      ...(options.compact
        ? { notation: 'compact', maximumFractionDigits: 1 }
        : { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
    })
    currencyFormatters.set(key, formatter)
  }
  return formatter.format(value)
}

const integer = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 })

export function formatNumber(value: number): string {
  return integer.format(value)
}

export function formatPercent(value: number, options: { signed?: boolean } = {}): string {
  const formatted = `${Math.abs(value).toFixed(1)}%`
  if (!options.signed) return `${value < 0 ? '-' : ''}${formatted}`
  if (value > 0) return `+${formatted}`
  if (value < 0) return `−${formatted}`
  return formatted
}

/** Parses an ISO `yyyy-MM-dd` date as a calendar date (no time-zone shift). */
export function parseIsoDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d))
}

export function toIsoDate(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export function addDays(iso: string, days: number): string {
  const date = parseIsoDate(iso)
  date.setUTCDate(date.getUTCDate() + days)
  return toIsoDate(date)
}

export function daysBetweenInclusive(from: string, to: string): number {
  return Math.round((parseIsoDate(to).getTime() - parseIsoDate(from).getTime()) / 86_400_000) + 1
}

const dateFormatter = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
const shortDateFormatter = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' })
const monthFormatter = new Intl.DateTimeFormat(locale, { month: 'short', year: 'numeric', timeZone: 'UTC' })

/** "Aug 31, 2026" */
export function formatDate(iso: string): string {
  return dateFormatter.format(parseIsoDate(iso))
}

/** "Aug 1 – Aug 31, 2026", collapsing the shared year. */
export function formatDateRange(from: string, to: string): string {
  if (from === to) return formatDate(from)
  const sameYear = from.slice(0, 4) === to.slice(0, 4)
  const start = sameYear ? shortDateFormatter.format(parseIsoDate(from)) : formatDate(from)
  return `${start} – ${formatDate(to)}`
}

/** Axis label for a bucket start. */
export function formatBucketTick(iso: string, granularity: Granularity): string {
  return granularity === 'month' ? monthFormatter.format(parseIsoDate(iso)) : shortDateFormatter.format(parseIsoDate(iso))
}

/** Tooltip/table label describing a whole bucket. */
export function formatBucketLabel(iso: string, granularity: Granularity): string {
  switch (granularity) {
    case 'day':
      return new Intl.DateTimeFormat(locale, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
        .format(parseIsoDate(iso))
    case 'week':
      return `Week of ${formatDate(iso)}`
    case 'month':
      return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(parseIsoDate(iso))
  }
}

const dateTimeFormatters = new Map<string, Intl.DateTimeFormat>()

/** Formats a UTC instant in the business's time zone: "Aug 31, 7:34 PM". */
export function formatDateTime(instant: string, timeZone: string): string {
  let formatter = dateTimeFormatters.get(timeZone)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      timeZone,
    })
    dateTimeFormatters.set(timeZone, formatter)
  }
  return formatter.format(new Date(instant))
}

const longDateTimeFormatters = new Map<string, Intl.DateTimeFormat>()

/** "Mon, Aug 31, 2026, 7:34 PM EDT" in the business's time zone. */
export function formatDateTimeLong(instant: string, timeZone: string): string {
  let formatter = longDateTimeFormatters.get(timeZone)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      timeZoneName: 'short',
      timeZone,
    })
    longDateTimeFormatters.set(timeZone, formatter)
  }
  return formatter.format(new Date(instant))
}
