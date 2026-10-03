import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { AuditEvent } from '../src/api/accountManagement.ts'
import { actorName, auditSentence, formatRelativeTime, parseAuditCategory } from '../src/lib/audit.ts'

function event(action: string, details: Record<string, unknown> = {}, actor: AuditEvent['actor'] = { id: 1, name: 'Ana' }): AuditEvent {
  return { id: 1, action, actor, targetType: 'business', targetId: 1, details, createdAt: '2026-10-01T10:00:00Z' }
}

describe('auditSentence', () => {
  const cases: [string, Record<string, unknown>, string][] = [
    [
      'business.created',
      { name: 'Shop', currency: 'EUR', timeZone: 'Europe/Paris' },
      'Ana created the business “Shop” with currency EUR and time zone Europe/Paris.',
    ],
    ['business.renamed', { from: 'A', to: 'B' }, 'Ana renamed the business from “A” to “B”.'],
    [
      'business.time_zone_changed',
      { from: 'America/New_York', to: 'Europe/Paris' },
      'Ana changed the time zone from America/New_York to Europe/Paris.',
    ],
    ['business.currency_changed', { from: 'USD', to: 'EUR' }, 'Ana changed the currency from USD to EUR.'],
    ['business.exported', {}, 'Ana exported the business data.'],
    ['member.invited', { email: 'bo@example.com', role: 'ADMIN' }, 'Ana invited bo@example.com as an admin.'],
    ['invitation.revoked', { email: 'bo@example.com', role: 'VIEWER' }, 'Ana revoked the invitation for bo@example.com (viewer).'],
    ['invitation.accepted', { role: 'VIEWER' }, 'Ana accepted an invitation and joined as a viewer.'],
    ['member.role_changed', { from: 'VIEWER', to: 'OWNER' }, 'Ana changed the role of a member from viewer to owner.'],
    ['member.removed', { role: 'ADMIN' }, 'Ana removed a member (admin) from the business.'],
    ['member.left', { role: 'ADMIN' }, 'Ana left the business (was an admin).'],
    ['member.account_deleted', { role: 'VIEWER' }, 'A member deleted their account and left the business (was a viewer).'],
    [
      'import.completed',
      { kind: 'SALES', mode: 'APPEND', fileName: 'march.csv', rows: 120, created: 118, updated: 2, errors: 0 },
      'Ana imported sales from “march.csv” (append): 120 rows, 118 created, 2 updated.',
    ],
    [
      'import.rejected',
      { kind: 'PRODUCTS', fileName: 'p.csv', rows: 10, created: 0, updated: 0, errors: 3 },
      "Ana's import of products from “p.csv” was rejected: 3 errors in 10 rows.",
    ],
    ['chart.created', { title: 'Revenue', revision: 1 }, 'Ana created the chart “Revenue”.'],
    ['chart.updated', { title: 'Revenue', revision: 3 }, 'Ana updated the chart “Revenue” (revision 3).'],
    ['chart.duplicated', { title: 'Revenue (copy)', fromChartId: 4 }, 'Ana duplicated a chart as “Revenue (copy)”.'],
    ['chart.deleted', { title: 'Revenue', revision: 3 }, 'Ana deleted the chart “Revenue”.'],
    ['dashboard.created', { name: 'Weekly', revision: 1, widgetCount: 1 }, 'Ana created the dashboard “Weekly” with 1 widget.'],
    ['dashboard.updated', { name: 'Weekly', revision: 2, widgetCount: 4 }, 'Ana updated the dashboard “Weekly” (revision 2, 4 widgets).'],
    [
      'dashboard.updated',
      { name: 'Weekly', revision: 3, widgetCount: 4, renamedFrom: 'Week' },
      'Ana renamed the dashboard “Week” to “Weekly” (revision 3, 4 widgets).',
    ],
    ['dashboard.duplicated', { name: 'Weekly (copy)', revision: 1, widgetCount: 4 }, 'Ana duplicated a dashboard as “Weekly (copy)” (4 widgets).'],
    ['dashboard.deleted', { name: 'Weekly', revision: 3, widgetCount: 4 }, 'Ana deleted the dashboard “Weekly”.'],
  ]
  for (const [action, details, expected] of cases) {
    it(action + (details.renamedFrom ? ' (renamed)' : ''), () => assert.equal(auditSentence(event(action, details)), expected))
  }

  it('names a deleted account and an unknown actor', () => {
    assert.equal(auditSentence(event('business.exported', {}, { id: 9, name: 'Deleted account' })), 'Deleted account exported the business data.')
    assert.equal(auditSentence(event('business.exported', {}, null)), 'Someone exported the business data.')
    assert.equal(actorName({ actor: null }), 'Someone')
  })

  it('uses a member name when the server provides one', () => {
    assert.equal(auditSentence(event('member.removed', { role: 'VIEWER', displayName: 'Bo' })), 'Ana removed Bo (viewer) from the business.')
  })

  it('tolerates missing details and unknown actions', () => {
    assert.equal(auditSentence(event('chart.deleted', {})), 'Ana deleted the chart.')
    assert.equal(auditSentence(event('saved_report.created')), 'Ana: saved_report.created.')
    assert.equal(
      auditSentence({ ...event('business.renamed'), details: null as unknown as Record<string, unknown> }),
      'Ana renamed the business from its old name to a new name.',
    )
  })
})

describe('parseAuditCategory', () => {
  it('accepts known categories only', () => {
    assert.equal(parseAuditCategory('member'), 'member')
    assert.equal(parseAuditCategory('members'), null)
    assert.equal(parseAuditCategory(null), null)
  })
})

describe('formatRelativeTime', () => {
  const now = Date.parse('2026-10-03T12:00:00Z')
  it('reads like speech', () => {
    assert.equal(formatRelativeTime('2026-10-03T11:59:40Z', now), 'just now')
    assert.equal(formatRelativeTime('2026-10-03T11:55:00Z', now), '5 minutes ago')
    assert.equal(formatRelativeTime('2026-10-03T09:00:00Z', now), '3 hours ago')
    assert.equal(formatRelativeTime('2026-10-02T10:00:00Z', now), 'yesterday')
    assert.equal(formatRelativeTime('2026-09-12T12:00:00Z', now), '3 weeks ago')
    assert.equal(formatRelativeTime('2025-09-01T12:00:00Z', now), 'last year')
  })
  it('returns nothing for an invalid instant', () => assert.equal(formatRelativeTime('nope', now), ''))
})
