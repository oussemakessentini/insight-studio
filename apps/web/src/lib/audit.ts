// Readable sentences for the audit history (contract §2). Pure: no imports with side effects, so
// node tests can load it directly (tests/audit.test.ts).
import type { AuditCategory, AuditEvent } from '../api/accountManagement'

export const AUDIT_CATEGORIES: { value: AuditCategory; label: string }[] = [
  { value: 'business', label: 'Business settings' },
  { value: 'member', label: 'Members' },
  { value: 'import', label: 'Imports' },
  { value: 'chart', label: 'Charts' },
  { value: 'dashboard', label: 'Dashboards' },
]

/** The `?category=` value if it is a known category, otherwise null (all activity). */
export function parseAuditCategory(value: string | null | undefined): AuditCategory | null {
  return AUDIT_CATEGORIES.find((c) => c.value === value)?.value ?? null
}

// Kept here (not imported from api/account) so this module has no runtime dependencies.
const ROLE_NAMES: Record<string, string> = { OWNER: 'owner', ADMIN: 'admin', VIEWER: 'viewer' }

function role(value: unknown): string {
  const text = typeof value === 'string' ? value : ''
  return ROLE_NAMES[text] ?? (text.toLowerCase() || 'member')
}

/** "an owner", "an admin", "a viewer". */
function withArticle(roleName: string): string {
  return `${/^[aeiou]/.test(roleName) ? 'an' : 'a'} ${roleName}`
}

function text(details: Record<string, unknown>, key: string): string | null {
  const value = details[key]
  if (typeof value === 'string' && value.trim()) return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return null
}

function count(details: Record<string, unknown>, key: string): number | null {
  const value = details[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function quoted(value: string | null, fallback: string): string {
  return value ? `“${value}”` : fallback
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`
}

/** "Product", "sales", … as the import kind reads in a sentence. */
function importKind(kind: string | null): string {
  switch (kind) {
    case 'SALES':
    case 'sales':
      return 'sales'
    case 'PRODUCTS':
    case 'products':
      return 'products'
    case 'STORES':
    case 'stores':
      return 'stores'
    default:
      return kind ? kind.toLowerCase() : 'data'
  }
}

/**
 * The member an event is about. The contract's details carry only roles for member events, so the
 * name is used only if the server adds one; otherwise "a member".
 */
function memberName(details: Record<string, unknown>): string {
  return text(details, 'displayName') ?? text(details, 'name') ?? text(details, 'email') ?? 'a member'
}

/** Who did it: the actor's name, "Deleted account" for a removed account, "Someone" when unknown. */
export function actorName(event: Pick<AuditEvent, 'actor'>): string {
  return event.actor?.name?.trim() || 'Someone'
}

/** One sentence describing the event, e.g. "Ana renamed the business from “A” to “B”." */
export function auditSentence(event: AuditEvent): string {
  const who = actorName(event)
  const d = event.details && typeof event.details === 'object' ? event.details : {}
  const from = text(d, 'from')
  const to = text(d, 'to')

  switch (event.action) {
    case 'business.created': {
      const extras = [text(d, 'currency') && `currency ${text(d, 'currency')}`, text(d, 'timeZone') && `time zone ${text(d, 'timeZone')}`]
        .filter(Boolean)
        .join(' and ')
      return `${who} created the business ${quoted(text(d, 'name'), '')}`.trimEnd() + (extras ? ` with ${extras}.` : '.')
    }
    case 'business.renamed':
      return `${who} renamed the business from ${quoted(from, 'its old name')} to ${quoted(to, 'a new name')}.`
    case 'business.time_zone_changed':
      return `${who} changed the time zone from ${from ?? 'the old zone'} to ${to ?? 'a new zone'}.`
    case 'business.currency_changed':
      return `${who} changed the currency from ${from ?? 'the old currency'} to ${to ?? 'a new currency'}.`
    case 'business.exported':
      return `${who} exported the business data.`

    case 'member.invited':
      return `${who} invited ${text(d, 'email') ?? 'someone'} as ${withArticle(role(d.role))}.`
    case 'invitation.revoked':
      return `${who} revoked the invitation for ${text(d, 'email') ?? 'someone'} (${role(d.role)}).`
    case 'invitation.accepted':
      return `${who} accepted an invitation and joined as ${withArticle(role(d.role))}.`
    case 'member.role_changed':
      return `${who} changed the role of ${memberName(d)} from ${role(d.from)} to ${role(d.to)}.`
    case 'member.removed':
      return `${who} removed ${memberName(d)} (${role(d.role)}) from the business.`
    case 'member.left':
      return `${who} left the business (was ${withArticle(role(d.role))}).`
    case 'member.account_deleted':
      return `A member deleted their account and left the business (was ${withArticle(role(d.role))}).`

    case 'import.completed':
    case 'import.rejected': {
      const kind = importKind(text(d, 'kind'))
      const file = quoted(text(d, 'fileName'), 'a file')
      const rows = count(d, 'rows')
      const errors = count(d, 'errors') ?? 0
      if (event.action === 'import.rejected') {
        const why = errors > 0 ? `: ${plural(errors, 'error')}${rows !== null ? ` in ${plural(rows, 'row')}` : ''}` : ''
        return `${who}'s import of ${kind} from ${file} was rejected${why}.`
      }
      const parts = [
        rows !== null ? plural(rows, 'row') : null,
        count(d, 'created') !== null ? `${count(d, 'created')!.toLocaleString('en-US')} created` : null,
        count(d, 'updated') !== null ? `${count(d, 'updated')!.toLocaleString('en-US')} updated` : null,
        errors > 0 ? plural(errors, 'error') : null,
      ].filter(Boolean)
      const mode = text(d, 'mode')
      const modeText = mode ? ` (${mode.toLowerCase().replaceAll('_', ' ')})` : ''
      return `${who} imported ${kind} from ${file}${modeText}${parts.length ? `: ${parts.join(', ')}` : ''}.`
    }

    case 'chart.created':
      return `${who} created the chart ${quoted(text(d, 'title'), 'without a title')}.`
    case 'chart.updated': {
      const revision = count(d, 'revision')
      return `${who} updated the chart ${quoted(text(d, 'title'), '')}`.trimEnd() + (revision !== null ? ` (revision ${revision}).` : '.')
    }
    case 'chart.duplicated':
      return `${who} duplicated a chart as ${quoted(text(d, 'title'), 'a copy')}.`
    case 'chart.deleted':
      return `${who} deleted the chart ${quoted(text(d, 'title'), '')}`.trimEnd() + '.'

    case 'dashboard.created':
    case 'dashboard.updated':
    case 'dashboard.duplicated':
    case 'dashboard.deleted': {
      const name = quoted(text(d, 'name'), '')
      const widgets = count(d, 'widgetCount')
      const widgetText = widgets !== null ? plural(widgets, 'widget') : null
      if (event.action === 'dashboard.created') {
        return `${who} created the dashboard ${name}`.trimEnd() + (widgetText ? ` with ${widgetText}.` : '.')
      }
      if (event.action === 'dashboard.duplicated') {
        return `${who} duplicated a dashboard as ${name || 'a copy'}` + (widgetText ? ` (${widgetText}).` : '.')
      }
      if (event.action === 'dashboard.deleted') return `${who} deleted the dashboard ${name}`.trimEnd() + '.'
      const revision = count(d, 'revision')
      const extras = [revision !== null ? `revision ${revision}` : null, widgetText].filter(Boolean).join(', ')
      const renamedFrom = text(d, 'renamedFrom')
      const verb = renamedFrom ? `renamed the dashboard ${quoted(renamedFrom, '')} to ${name}` : `updated the dashboard ${name}`
      return `${who} ${verb}`.trimEnd() + (extras ? ` (${extras}).` : '.')
    }

    default:
      return `${who}: ${event.action}.`
  }
}

const relative = new Intl.RelativeTimeFormat('en-US', { numeric: 'auto' })

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
]

/** "just now", "5 minutes ago", "yesterday", "3 weeks ago". */
export function formatRelativeTime(instant: string, now: number = Date.now()): string {
  const seconds = Math.round((new Date(instant).getTime() - now) / 1000)
  if (!Number.isFinite(seconds)) return ''
  if (Math.abs(seconds) < 45) return 'just now'
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size || unit === 'minute') {
      return relative.format(Math.round(seconds / size), unit)
    }
  }
  return ''
}
