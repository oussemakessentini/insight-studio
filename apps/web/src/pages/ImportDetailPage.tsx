import { ApiError } from '../api/client'
import { importsApi, type ImportDetail } from '../api/imports'
import { importedSalesLink } from '../components/imports/salesRange'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { StatTiles } from '../components/StatTiles'
import { useApi } from '../hooks/useApi'
import { formatCurrency, formatDateTimeLong, formatNumber } from '../lib/format'
import '../styles/imports.css'
import type { PageProps } from './types'

/** One successful import: what it added and when the imported receipts were sold. */
export function ImportDetailPage({ importId, context, onFiltersChange, href }: PageProps & { importId: number }) {
  const { business } = context
  const batch = useApi(`import|${importId}`, (signal) => importsApi.detail(importId, signal))

  if (batch.error instanceof ApiError && batch.error.status === 404) {
    return <ImportNotFound importsHref={href('/imports')} />
  }

  const data = batch.data

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={href('/imports')}>Import sales</Link>
            <span aria-hidden="true">/</span>
            <span>Import #{importId}</span>
          </nav>
        }
        title={data ? <span className="imports-title">{data.fileName}</span> : <Skeleton height={30} width={260} />}
        subtitle={data ? <>Imported {formatDateTimeLong(data.createdAt, business.timeZone)}</> : undefined}
      />

      {batch.error ? (
        <div className="panel">
          <ErrorState message={batch.error.message} onRetry={batch.retry} />
        </div>
      ) : data ? (
        <StatTiles
          tiles={[
            { label: 'Total', value: formatCurrency(data.totalAmount, business.currency), caption: 'At the prices charged' },
            { label: 'Receipts', value: formatNumber(data.saleCount) },
            { label: 'Line items', value: formatNumber(data.lineCount) },
            { label: 'Rows', value: formatNumber(data.rowCount), caption: 'In the file, header excluded' },
          ]}
        />
      ) : (
        <div className="metric-grid" aria-busy="true">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="metric-card">
              <Skeleton height={14} width="50%" />
              <Skeleton height={32} width="70%" />
            </div>
          ))}
        </div>
      )}

      <Panel title="Details">
        <AsyncContent {...batch} skeleton={<SkeletonRows rows={4} />}>
          {(d) => <Details batch={d} timeZone={business.timeZone} onFiltersChange={onFiltersChange} />}
        </AsyncContent>
      </Panel>
    </>
  )
}

function Details({
  batch,
  timeZone,
  onFiltersChange,
}: {
  batch: ImportDetail
  timeZone: string
  onFiltersChange: PageProps['onFiltersChange']
}) {
  const sales =
    batch.firstSoldAt && batch.lastSoldAt
      ? importedSalesLink(batch.firstSoldAt, batch.lastSoldAt, timeZone, onFiltersChange)
      : null
  return (
    <>
      <dl className="imports-details">
        <div>
          <dt>File</dt>
          <dd className="imports-file-name">{batch.fileName}</dd>
        </div>
        <div>
          <dt>Imported</dt>
          <dd>{formatDateTimeLong(batch.createdAt, timeZone)}</dd>
        </div>
        <div>
          <dt>First sale</dt>
          <dd>{batch.firstSoldAt ? formatDateTimeLong(batch.firstSoldAt, timeZone) : '–'}</dd>
        </div>
        <div>
          <dt>Last sale</dt>
          <dd>{batch.lastSoldAt ? formatDateTimeLong(batch.lastSoldAt, timeZone) : '–'}</dd>
        </div>
      </dl>
      {sales && (
        <p className="imports-buttons">
          <Link className="button button-secondary" href={sales.href} onClick={sales.onClick}>
            View sales in this period
          </Link>
        </p>
      )}
    </>
  )
}

function ImportNotFound({ importsHref }: { importsHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Import not found</h1>
      <ErrorState message="This import does not exist or belongs to another business." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={importsHref}>
          Back to imports
        </Link>
      </p>
    </div>
  )
}
