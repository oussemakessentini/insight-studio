import { useEffect, useId, useState } from 'react'
import { auditApi, type AuditCategory, type AuditEvent } from '../api/accountManagement'
import { FormError } from '../components/Form'
import { PageHeader } from '../components/PageHeader'
import { EmptyState, ErrorState, Panel, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { useStateResetOn } from '../hooks/useStateResetOn'
import { actorName, AUDIT_CATEGORIES, auditSentence, formatRelativeTime, parseAuditCategory } from '../lib/audit'
import { formatDateTimeLong } from '../lib/format'
import { updateQuery, useHistoryVersion } from '../lib/router'
import { useLoadedSession } from '../lib/session'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/settings.css'
import type { PageProps } from './types'

const PAGE_SIZE = 50

function categoryFromUrl(): AuditCategory | null {
  return parseAuditCategory(new URLSearchParams(window.location.search).get('category'))
}

/** Older pages fetched with "Load more", on top of the first page. */
interface MorePages {
  events: AuditEvent[]
  /** Undefined until a "Load more" answered: the first page's `nextBefore` applies. */
  nextBefore: number | null | undefined
}

const NO_MORE_PAGES: MorePages = { events: [], nextBefore: undefined }

/** Settings › Activity (contract §2, §5): who changed what in this business, newest first. */
export function ActivityPage({ context }: PageProps) {
  const { businessId } = useLoadedSession()
  const timeZone = context.business.timeZone
  const selectId = useId()

  // The category lives in the URL (?category=member), re-read on back/forward.
  const historyVersion = useHistoryVersion()
  const [category, setCategory] = useStateResetOn<AuditCategory | null>(String(historyVersion), categoryFromUrl())
  useEffect(() => updateQuery({ category }), [category])

  const first = useApi(`audit|${businessId}|${category ?? 'all'}`, (signal) =>
    auditApi.list(businessId!, { category, limit: PAGE_SIZE }, signal),
  )
  const [more, setMore] = useStateResetOn<MorePages>(category ?? 'all', NO_MORE_PAGES)
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreError, setMoreError] = useState<string | null>(null)
  // Ticks so relative times ("5 minutes ago") stay current while the page is open.
  const now = useNow(60_000)

  const nextBefore = more.nextBefore !== undefined ? more.nextBefore : (first.data?.nextBefore ?? null)
  const events = dedupe([...(first.data?.events ?? []), ...more.events])

  const loadMore = async () => {
    if (nextBefore === null) return
    setLoadingMore(true)
    setMoreError(null)
    try {
      const page = await auditApi.list(businessId!, { category, before: nextBefore, limit: PAGE_SIZE })
      setMore({ events: [...more.events, ...page.events], nextBefore: page.nextBefore })
    } catch (err) {
      setMoreError(errorMessage(err))
    } finally {
      setLoadingMore(false)
    }
  }

  const categoryLabel = AUDIT_CATEGORIES.find((c) => c.value === category)?.label

  return (
    <>
      <PageHeader eyebrow="Settings" title="Activity" subtitle={`Who changed what in ${context.business.name}, newest first.`}>
        <div className="activity-filter">
          <label className="form-label" htmlFor={selectId}>
            Show
          </label>
          <select
            id={selectId}
            className="control"
            value={category ?? ''}
            onChange={(e) => {
              setCategory(parseAuditCategory(e.target.value))
              setMoreError(null)
            }}
          >
            <option value="">All activity</option>
            {AUDIT_CATEGORIES.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
        </div>
      </PageHeader>

      <Panel title="History" subtitle={`Times are in the business time zone (${timeZone.replaceAll('_', ' ')}).`}>
        {first.error && !first.loading ? (
          <ErrorState message={first.error.message} onRetry={first.retry} />
        ) : first.data === undefined || (first.loading && events.length === 0) ? (
          <SkeletonRows rows={6} />
        ) : events.length === 0 ? (
          <EmptyState
            message={categoryLabel ? `No activity in “${categoryLabel}” yet.` : 'No activity yet. Changes to this business will appear here.'}
          />
        ) : (
          <div className={first.loading ? 'is-refreshing' : undefined} aria-busy={first.loading}>
            <ol className="activity-list" aria-label="Activity, newest first">
              {events.map((event) => (
                <li key={event.id} className="activity-item">
                  <p className="activity-sentence">{auditSentence(event)}</p>
                  <p className="activity-meta">
                    <span className={event.actor?.name === 'Deleted account' ? 'activity-actor is-deleted' : 'activity-actor'}>
                      {actorName(event)}
                    </span>
                    <span aria-hidden="true"> · </span>
                    <time dateTime={event.createdAt} title={formatDateTimeLong(event.createdAt, timeZone)}>
                      {formatRelativeTime(event.createdAt, now)}
                    </time>
                    <span aria-hidden="true"> · </span>
                    <span className="activity-absolute">{formatDateTimeLong(event.createdAt, timeZone)}</span>
                  </p>
                </li>
              ))}
            </ol>
            <div className="activity-more" aria-live="polite">
              {moreError && <FormError>{moreError}</FormError>}
              {nextBefore !== null ? (
                <button type="button" className="button button-secondary" disabled={loadingMore || first.loading} aria-busy={loadingMore} onClick={() => void loadMore()}>
                  {loadingMore ? 'Loading…' : 'Load more'}
                </button>
              ) : (
                <p className="text-secondary activity-end">That's everything.</p>
              )}
            </div>
          </div>
        )}
      </Panel>
    </>
  )
}

function dedupe(events: AuditEvent[]): AuditEvent[] {
  const seen = new Set<number>()
  return events.filter((e) => (seen.has(e.id) ? false : (seen.add(e.id), true)))
}

function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])
  return now
}
