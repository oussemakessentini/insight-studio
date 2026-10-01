import { useState } from 'react'
import { reportsApi, type ReportKind } from '../api/reports'
import { FilterBar } from '../components/FilterBar'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, Panel, SkeletonRows } from '../components/Panel'
import { CategoryReportTable } from '../components/reports/CategoryReportTable'
import { Link } from '../components/Link'
import { ExportLinks } from '../components/reports/ExportLink'
import { MonthlyReportTable } from '../components/reports/MonthlyReportTable'
import { ReportTabs } from '../components/reports/ReportTabs'
import { SaveReportDialog } from '../components/reports/SaveReportDialog'
import { DEFAULT_REPORT, panelId, reportFromUrl, tabId } from '../components/reports/tabs'
import { useApi } from '../hooks/useApi'
import { useStateResetOn } from '../hooks/useStateResetOn'
import type { Filters } from '../lib/filters'
import { formatDateRange } from '../lib/format'
import { updateQuery, useHistoryVersion } from '../lib/router'
import { savedReportPermissions } from '../lib/savedReports'
import '../styles/reports.css'
import { apiFilter, filterKey, type PageProps } from './types'

export function ReportsPage({ context, filters, onFiltersChange, href }: PageProps) {
  const { business, stores, dataRange } = context
  const saving = savedReportPermissions(context.access)
  const [saveOpen, setSaveOpen] = useState(false)
  // The selected report lives in `?report=` so refresh, shared links and back/forward restore it.
  const historyVersion = useHistoryVersion()
  const [report, setReportState] = useStateResetOn<ReportKind>(String(historyVersion), reportFromUrl())
  const setReport = (next: ReportKind) => {
    setReportState(next)
    updateQuery({ report: next !== DEFAULT_REPORT ? next : null })
  }

  const selectedStore = stores.find((s) => s.id === filters.storeId)
  const csvHref = reportsApi.csvUrl(report, apiFilter(filters))
  const pdfHref = reportsApi.pdfUrl(report, apiFilter(filters))

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Reports"
        subtitle={
          <>
            {selectedStore ? selectedStore.name : 'All stores'} · {formatDateRange(filters.from, filters.to)}
            <span className="page-subtitle-muted"> · Sales at the prices charged</span>
          </>
        }
      >
        <FilterBar filters={filters} stores={stores} dataRange={dataRange} onChange={onFiltersChange} />
      </PageHeader>

      <div className="reports-toolbar">
        <ReportTabs value={report} onChange={setReport} />
        {/* The public demo has no saved reports (the API answers 401), so it shows no way in. */}
        {saving.available && (
          <div className="reports-toolbar-actions">
            <Link className="button button-secondary button-small" href={href('/reports/saved')}>
              Saved reports
            </Link>
            {(saving.canManage || saving.needsVerification) && (
              <button
                type="button"
                className="button button-primary button-small"
                aria-haspopup="dialog"
                onClick={() => setSaveOpen(true)}
              >
                Save report
              </button>
            )}
          </div>
        )}
      </div>

      <div role="tabpanel" id={panelId(report)} aria-labelledby={tabId(report)}>
        {report === 'monthly' ? (
          <Panel
            title="Monthly report"
            subtitle={`Calendar months in ${business.timeZone}`}
            actions={<ExportLinks csvHref={csvHref} pdfHref={pdfHref} label="monthly report" />}
          >
            <MonthlyReport filters={filters} currency={business.currency} />
          </Panel>
        ) : (
          <Panel
            title="Category report"
            subtitle="Every catalogue category, by revenue"
            actions={<ExportLinks csvHref={csvHref} pdfHref={pdfHref} label="category report" />}
          >
            <CategoryReport filters={filters} currency={business.currency} />
          </Panel>
        )}
      </div>

      {saving.available && (
        <SaveReportDialog
          open={saveOpen}
          onClose={() => setSaveOpen(false)}
          kind={report}
          filters={filters}
          stores={stores}
          needsVerification={saving.needsVerification}
          href={href}
        />
      )}
    </>
  )
}

interface ReportProps {
  filters: Filters
  currency: string
}

function MonthlyReport({ filters, currency }: ReportProps) {
  const monthly = useApi(`report-monthly|${filterKey(filters)}`, (signal) =>
    reportsApi.monthly(apiFilter(filters), signal),
  )
  return (
    <AsyncContent {...monthly} skeleton={<SkeletonRows rows={6} />} isEmpty={(data) => data.totals.orders === 0}>
      {(data) => <MonthlyReportTable report={data} currency={currency} />}
    </AsyncContent>
  )
}

function CategoryReport({ filters, currency }: ReportProps) {
  const categories = useApi(`report-categories|${filterKey(filters)}`, (signal) =>
    reportsApi.categories(apiFilter(filters), signal),
  )
  const emptyCatalogue = categories.data?.rows.length === 0
  return (
    <AsyncContent
      {...categories}
      skeleton={<SkeletonRows rows={6} />}
      isEmpty={(data) => data.totals.orders === 0}
      emptyMessage={emptyCatalogue ? 'There are no products in the catalogue yet.' : 'No sales in this period.'}
    >
      {(data) => <CategoryReportTable report={data} currency={currency} />}
    </AsyncContent>
  )
}
