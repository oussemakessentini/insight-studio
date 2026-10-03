import { useMemo, useState, type FormEvent } from 'react'
import { ROLE_LABELS } from '../api/account'
import {
  businessSettingsApi,
  type BusinessDeletionPreview,
  type BusinessSettings,
  type TimeZonePreview,
} from '../api/accountManagement'
import { Dialog } from '../components/Dialog'
import { FormError, FormSuccess, SelectField, SubmitButton, TextField } from '../components/Form'
import { DownloadIcon } from '../components/Icons'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { useApi, type ApiState } from '../hooks/useApi'
import {
  allTimeZones,
  businessNameConfirmed,
  businessNameError,
  currencyOptions,
  filterTimeZones,
  settingsChange,
  timeZoneLabel,
} from '../lib/accountSettings'
import { plural } from '../lib/audit'
import { formatBucketTick, formatCurrency, formatDateTimeLong, formatNumber } from '../lib/format'
import { useLoadedSession } from '../lib/session'
import { canManageBusiness } from '../lib/settingsAccess'
import { errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import '../styles/reports.css'
import '../styles/settings.css'
import type { PageProps } from './types'

type Notice = { kind: 'success' | 'error'; message: string } | null

/**
 * Settings › Business (contract §5): owners change the name, time zone and (while no amounts exist)
 * the currency, export everything and delete the business; admins read the settings.
 */
export function BusinessSettingsPage({ context, refreshContext }: PageProps) {
  const { businessId, reload } = useLoadedSession()
  const [version, setVersion] = useState(0)
  const [notice, setNotice] = useState<Notice>(null)
  const settings = useApi(`business-settings|${businessId}|${version}`, (signal) => businessSettingsApi.get(businessId!, signal))
  const owner = context.access.role === 'OWNER'
  const canEdit = canManageBusiness(context.access)

  const onSaved = (message: string) => {
    setNotice({ kind: 'success', message })
    setVersion((v) => v + 1)
    // The whole app (sidebar, reports, dashboards) reads the name, zone and currency from these.
    refreshContext()
    void reload().catch(() => undefined)
  }

  return (
    <>
      <PageHeader
        eyebrow="Settings"
        title="Business"
        subtitle={owner ? 'The name, time zone and currency of this business.' : 'Only owners can change these settings.'}
      />

      <Panel title="Business settings" className="settings-panel">
        <div className="form-stack">
          <div aria-live="polite">
            {notice?.kind === 'success' && <FormSuccess>{notice.message}</FormSuccess>}
            {notice?.kind === 'error' && <FormError>{notice.message}</FormError>}
          </div>
          <AsyncContent {...settings} skeleton={<SkeletonRows rows={4} />}>
            {(data) =>
              owner ? (
                // Keyed by the saved values: after a save the form starts again from them.
                <SettingsForm
                  key={`${data.name}|${data.timeZone}|${data.currency}`}
                  settings={data}
                  canEdit={canEdit}
                  onSaved={onSaved}
                  onEdit={() => setNotice(null)}
                />
              ) : (
                <SettingsSummary settings={data} />
              )
            }
          </AsyncContent>
        </div>
      </Panel>

      {owner && settings.data && <DangerZone settings={settings.data} canEdit={canEdit} />}
    </>
  )
}

/** What an admin sees: the settings, read-only. */
function SettingsSummary({ settings }: { settings: BusinessSettings }) {
  return (
    <dl className="details-list settings-summary">
      <div>
        <dt>Business name</dt>
        <dd className="break-anywhere">{settings.name}</dd>
      </div>
      <div>
        <dt>Time zone</dt>
        <dd>{timeZoneLabel(settings.timeZone)}</dd>
      </div>
      <div>
        <dt>Currency</dt>
        <dd>{settings.currency}</dd>
      </div>
      <div>
        <dt>Created</dt>
        <dd>{formatDateTimeLong(settings.createdAt, settings.timeZone)}</dd>
      </div>
      <div>
        <dt>Your role</dt>
        <dd>{ROLE_LABELS[settings.role]}</dd>
      </div>
    </dl>
  )
}

function SettingsForm({
  settings,
  canEdit,
  onSaved,
  onEdit,
}: {
  settings: BusinessSettings
  canEdit: boolean
  onSaved: (message: string) => void
  onEdit: () => void
}) {
  const { businessId } = useLoadedSession()
  const zones = useMemo(() => allTimeZones(settings.timeZone), [settings.timeZone])
  const currencies = useMemo(() => {
    const options = currencyOptions()
    return options.some((c) => c.code === settings.currency) ? options : [{ code: settings.currency, label: settings.currency }, ...options]
  }, [settings.currency])
  const [name, setName] = useState(settings.name)
  const [zoneSearch, setZoneSearch] = useState('')
  const [timeZone, setTimeZone] = useState(settings.timeZone)
  const [currency, setCurrency] = useState(settings.currency)
  const [nameTouched, setNameTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const zoneChanged = timeZone !== settings.timeZone
  // The impact of the new zone, loaded as soon as one is picked and shown before saving.
  const preview = useApi<TimeZonePreview | null>(`tz-preview|${businessId}|${timeZone}`, (signal) =>
    zoneChanged ? businessSettingsApi.timeZonePreview(businessId!, timeZone, signal) : Promise.resolve(null),
  )
  const previewReady = !zoneChanged || (!preview.loading && !preview.error && Boolean(preview.data))

  const shownZones = filterTimeZones(zones, zoneSearch, timeZone)
  const nameError = businessNameError(name)
  const change = settingsChange(settings, { name, timeZone, currency })
  const changed = Object.keys(change).length > 0
  const disabled = !canEdit || busy

  const edit = <T,>(set: (value: T) => void) => (value: T) => {
    set(value)
    setError(null)
    onEdit()
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setNameTouched(true)
    if (nameError || !changed || !previewReady) return
    setBusy(true)
    setError(null)
    try {
      await businessSettingsApi.update(businessId!, change)
      onSaved(savedMessage(settings, change))
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate aria-label="Business settings">
      {!canEdit && (
        <p className="form-hint settings-note">Verify your email address to change these settings.</p>
      )}
      {error && <FormError>{error}</FormError>}
      <TextField
        label="Business name"
        name="organization"
        autoComplete="organization"
        value={name}
        onChange={edit(setName)}
        onBlur={() => setNameTouched(true)}
        error={nameTouched ? nameError : null}
        maxLength={220}
        disabled={disabled}
      />

      <fieldset className="settings-fieldset">
        <legend className="form-label">Time zone</legend>
        <div className="form-columns">
          <TextField
            label="Search time zones"
            type="search"
            value={zoneSearch}
            onChange={setZoneSearch}
            placeholder="e.g. Paris, New York"
            autoComplete="off"
            spellCheck={false}
            hint={`${plural(shownZones.length, 'zone')} listed.`}
            disabled={disabled}
          />
          <SelectField
            label="Business time zone"
            value={timeZone}
            onChange={edit(setTimeZone)}
            hint={zoneChanged ? `Currently ${timeZoneLabel(settings.timeZone)}.` : 'Days, weeks and months in reports follow it.'}
            disabled={disabled}
          >
            {shownZones.map((zone) => (
              <option key={zone} value={zone}>
                {timeZoneLabel(zone)}
              </option>
            ))}
          </SelectField>
        </div>
        {zoneChanged && <TimeZoneImpact from={settings.timeZone} to={timeZone} currency={settings.currency} preview={preview} />}
      </fieldset>

      <SelectField
        label="Currency"
        value={currency}
        onChange={edit(setCurrency)}
        hint={
          settings.currencyChangeAllowed
            ? 'The business has no products or sales yet, so the currency can still change.'
            : (settings.currencyLockedReason ??
              "The currency can't change once the business has products or sales. Create a new business for another currency.")
        }
        disabled={disabled || !settings.currencyChangeAllowed}
      >
        {currencies.map((c) => (
          <option key={c.code} value={c.code}>
            {c.label}
          </option>
        ))}
      </SelectField>

      <div className="form-actions">
        <SubmitButton busy={busy} busyLabel="Saving…" disabled={!canEdit || !changed || !previewReady}>
          Save changes
        </SubmitButton>
        {changed && !busy && (
          <button
            type="button"
            className="button button-secondary"
            onClick={() => {
              setName(settings.name)
              setTimeZone(settings.timeZone)
              setCurrency(settings.currency)
              setZoneSearch('')
              setNameTouched(false)
              setError(null)
            }}
          >
            Discard changes
          </button>
        )}
      </div>
      {zoneChanged && !previewReady && !preview.error && (
        <p className="form-hint" role="status">
          Checking how the new time zone affects your figures…
        </p>
      )}
    </form>
  )
}

function savedMessage(settings: BusinessSettings, change: { name?: string; timeZone?: string; currency?: string }): string {
  const parts = [
    change.name !== undefined && `renamed to “${change.name}”`,
    change.timeZone !== undefined && `time zone ${timeZoneLabel(change.timeZone)}`,
    change.currency !== undefined && `currency ${change.currency}`,
  ].filter(Boolean)
  return parts.length ? `Saved: ${parts.join(', ')}.` : `Saved ${settings.name}.`
}

/** What switching zones does to the figures (contract §1: re-bucketed, never rewritten). */
function TimeZoneImpact({
  from,
  to,
  currency,
  preview,
}: {
  from: string
  to: string
  currency: string
  preview: ApiState<TimeZonePreview | null>
}) {
  // useApi keeps the previous zone's answer while the next loads: only show the current one.
  const data = preview.loading ? null : (preview.data ?? null)
  return (
    <section className="tz-impact" aria-label="Impact of the new time zone" aria-busy={!data && !preview.error}>
      <h3 className="tz-impact-title">
        Switching from {timeZoneLabel(from)} to {timeZoneLabel(to)}
      </h3>
      <ul className="tz-impact-rules">
        <li>Nothing stored is rewritten: every sale keeps its exact time, and totals over all your sales stay the same.</li>
        <li>
          Days, weeks and months in reports, charts and dashboards are counted in the new zone, so a sale near midnight can
          move to the previous or next day, and at a month boundary to another month.
        </li>
        <li>
          Fixed dates in saved reports and charts (like 2026-03-01) are read as dates in the new zone; rolling ranges such as
          “Last 30 days” follow today in the new zone.
        </li>
      </ul>
      <div aria-live="polite">
        {preview.error ? (
          <ErrorState message={`Couldn't check the impact: ${preview.error.message}`} onRetry={preview.retry} />
        ) : !data ? (
          <div className="tz-impact-loading">
            <Skeleton height={16} width="70%" />
            <Skeleton height={16} width="50%" />
          </div>
        ) : data.salesTotal === 0 ? (
          <p className="tz-impact-summary">There are no sales yet, so no figures change.</p>
        ) : (
          <>
            <p className="tz-impact-summary">
              Of your {formatNumber(data.salesTotal)} sales, <strong>{plural(data.salesChangingDay, 'sale')}</strong>{' '}
              {data.salesChangingDay === 1 ? 'moves' : 'move'} to another day and{' '}
              <strong>{plural(data.salesChangingMonth, 'sale')}</strong> to another month.
            </p>
            {data.months.length > 0 ? (
              <div className="table-scroll">
                <table className="data-table tz-impact-table">
                  <caption className="visually-hidden">Monthly totals that change</caption>
                  <thead>
                    <tr>
                      <th scope="col">Month</th>
                      <th scope="col" className="num">
                        Revenue now
                      </th>
                      <th scope="col" className="num">
                        Revenue after
                      </th>
                      <th scope="col" className="num">
                        Orders now
                      </th>
                      <th scope="col" className="num">
                        Orders after
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.months.map((m) => (
                      <tr key={m.month}>
                        <th scope="row">{formatBucketTick(`${m.month}-01`, 'month')}</th>
                        <td className="num">{formatCurrency(Number(m.revenueBefore), currency)}</td>
                        <td className="num">{formatCurrency(Number(m.revenueAfter), currency)}</td>
                        <td className="num">{formatNumber(m.ordersBefore)}</td>
                        <td className="num">{formatNumber(m.ordersAfter)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-secondary">No monthly total changes.</p>
            )}
          </>
        )}
      </div>
    </section>
  )
}

// ---- Danger zone ------------------------------------------------------------------------------

function DangerZone({ settings, canEdit }: { settings: BusinessSettings; canEdit: boolean }) {
  const { businessId } = useLoadedSession()
  const [exporting, setExporting] = useState(false)
  const [exportNotice, setExportNotice] = useState<Notice>(null)
  const [deleting, setDeleting] = useState(false)

  const exportData = async () => {
    setExporting(true)
    setExportNotice(null)
    try {
      await businessSettingsApi.exportData(businessId!, settings.slug)
      setExportNotice({ kind: 'success', message: 'Your export has been downloaded.' })
    } catch (err) {
      setExportNotice({ kind: 'error', message: errorMessage(err) })
    } finally {
      setExporting(false)
    }
  }

  return (
    <Panel title="Danger zone" subtitle="Only owners see this." className="danger-zone">
      <div className="danger-list">
        <div className="danger-item">
          <div className="danger-text">
            <h3 className="danger-title">Export business data</h3>
            <p className="text-secondary">
              A ZIP of CSV and JSON files: settings, members, invitations, stores, products, sales, imports, saved reports,
              charts, dashboards and the activity history. Up to 5 exports per hour.
            </p>
            <div aria-live="polite">
              {exportNotice?.kind === 'success' && <FormSuccess>{exportNotice.message}</FormSuccess>}
              {exportNotice?.kind === 'error' && <FormError>{exportNotice.message}</FormError>}
            </div>
          </div>
          <button
            type="button"
            className="button button-secondary button-with-icon"
            disabled={!canEdit || exporting}
            aria-busy={exporting}
            onClick={() => void exportData()}
          >
            <DownloadIcon width={16} height={16} />
            {exporting ? 'Preparing export…' : 'Export business data'}
          </button>
        </div>
        <div className="danger-item">
          <div className="danger-text">
            <h3 className="danger-title">Delete business</h3>
            <p className="text-secondary">
              Permanently deletes {settings.name} with its stores, products, sales, imports, reports, charts, dashboards and
              history, for every member. It can't be undone.
            </p>
          </div>
          <button type="button" className="button button-danger" disabled={!canEdit} onClick={() => setDeleting(true)}>
            Delete business
          </button>
        </div>
      </div>
      <DeleteBusinessDialog open={deleting} businessName={settings.name} onClose={() => setDeleting(false)} />
    </Panel>
  )
}

function DeleteBusinessDialog({ open, businessName, onClose }: { open: boolean; businessName: string; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open={open} title={`Delete ${businessName}?`} onClose={onClose} busy={busy}>
      <DeleteBusinessForm onClose={onClose} onBusyChange={setBusy} />
    </Dialog>
  )
}

const COUNT_LABELS: [keyof BusinessDeletionPreview['counts'], string, string?][] = [
  ['members', 'member'],
  ['pendingInvitations', 'pending invitation'],
  ['stores', 'store'],
  ['products', 'product'],
  ['sales', 'sale'],
  ['imports', 'import'],
  ['savedReports', 'saved report'],
  ['charts', 'chart'],
  ['dashboards', 'dashboard'],
  ['auditEvents', 'activity entry', 'activity entries'],
]

function DeleteBusinessForm({ onClose, onBusyChange }: { onClose: () => void; onBusyChange: (busy: boolean) => void }) {
  const { businessId, reload } = useLoadedSession()
  const preview = useApi(`deletion-preview|${businessId}`, (signal) => businessSettingsApi.deletionPreview(businessId!, signal))
  const [password, setPassword] = useState('')
  const [confirmName, setConfirmName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (preview.error) return <ErrorState message={preview.error.message} onRetry={preview.retry} />
  if (!preview.data) return <SkeletonRows rows={4} />
  const { business, counts, otherMembers } = preview.data
  const confirmed = businessNameConfirmed(confirmName, business.name)
  const held = COUNT_LABELS.filter(([key]) => counts[key] > 0)

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!confirmed || !password) return
    setBusy(true)
    onBusyChange(true)
    setError(null)
    try {
      await businessSettingsApi.delete(business.id, password, confirmName.trim())
      // The membership is gone: move to another business, or to creating one when none is left.
      await reload((session) => (session.memberships.length > 0 ? '/' : '/businesses/new'))
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
      onBusyChange(false)
    }
  }

  return (
    <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
      <p className="dialog-text">
        This permanently deletes <strong className="break-anywhere">{business.name}</strong> and everything in it. It can't be
        undone; export the data first if you may need it.
      </p>
      {held.length > 0 ? (
        <ul className="deletion-counts" aria-label="What will be deleted">
          {held.map(([key, one, many]) => (
            <li key={key}>{plural(counts[key], one, many)}</li>
          ))}
        </ul>
      ) : (
        <p className="dialog-text">It holds no data yet.</p>
      )}
      {otherMembers.length > 0 && (
        <div className="deletion-members">
          <p className="dialog-text">
            {otherMembers.length === 1 ? 'This member loses' : `These ${otherMembers.length} members lose`} access:
          </p>
          <ul className="deletion-member-list">
            {otherMembers.map((m) => (
              <li key={m.userId}>
                <span className="break-anywhere">{m.displayName}</span>{' '}
                <span className={`role-badge role-${m.role.toLowerCase()}`}>{ROLE_LABELS[m.role]}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {error && <FormError>{error}</FormError>}
      <TextField
        label="Your password"
        type="password"
        name="current-password"
        autoComplete="current-password"
        value={password}
        onChange={setPassword}
        disabled={busy}
        autoFocus
      />
      <TextField
        label="Type the business name to confirm"
        value={confirmName}
        onChange={setConfirmName}
        hint={
          <>
            Type <strong className="break-anywhere">{business.name}</strong> exactly.
          </>
        }
        autoComplete="off"
        spellCheck={false}
        disabled={busy}
      />
      <div className="form-actions dialog-actions">
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button type="submit" className="button button-danger" disabled={busy || !confirmed || !password} aria-busy={busy}>
          {busy ? 'Deleting…' : 'Delete business'}
        </button>
      </div>
    </form>
  )
}
