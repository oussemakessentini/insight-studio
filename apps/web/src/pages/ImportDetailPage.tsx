import { ApiError } from '../api/client'
import { importsApi, type ImportDetail } from '../api/imports'
import { ImportStatusBadge } from '../components/imports/ImportResultView'
import { KINDS, MODE_LABELS, hasModes } from '../components/imports/kinds'
import { importedSalesLink } from '../components/imports/salesRange'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { StatTiles, type StatTile } from '../components/StatTiles'
import { useApi } from '../hooks/useApi'
import { formatCurrency, formatDateTimeLong, formatNumber } from '../lib/format'
import '../styles/imports.css'
import type { PageProps } from './types'

/**
 * One import attempt of any type: what it added or changed, or, for a rejected file, how many
 * errors stopped it. Sales imports also show when the imported receipts were sold.
 */
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
            <Link href={href('/imports')}>Import</Link>
            <span aria-hidden="true">/</span>
            <span>Import #{importId}</span>
          </nav>
        }
        title={data ? <span className="imports-title">{data.fileName}</span> : <Skeleton height={30} width={260} />}
        subtitle={
          data ? (
            <>
              {KINDS[data.kind].label} · {data.status === 'REJECTED' ? 'Rejected' : 'Imported'}{' '}
              {formatDateTimeLong(data.createdAt, business.timeZone)}
              {data.importedBy && <> by {data.importedBy}</>}
            </>
          ) : undefined
        }
      />

      {batch.error ? (
        <div className="panel">
          <ErrorState message={batch.error.message} onRetry={batch.retry} />
        </div>
      ) : data ? (
        <StatTiles tiles={tilesFor(data, business.currency)} />
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
          {(d) => <Details batch={d} timeZone={business.timeZone} href={href} onFiltersChange={onFiltersChange} />}
        </AsyncContent>
      </Panel>
    </>
  )
}

function tilesFor(batch: ImportDetail, currency: string): StatTile[] {
  const rows: StatTile = { label: 'Rows', value: formatNumber(batch.rowCount), caption: 'In the file, header excluded' }
  if (batch.status === 'REJECTED') {
    return [{ label: 'Errors', value: formatNumber(batch.errorCount), caption: 'Nothing was written' }, rows]
  }
  if (batch.kind === 'sales') {
    return [
      { label: 'Total', value: formatCurrency(batch.totalAmount, currency), caption: 'At the prices charged' },
      { label: 'Receipts', value: formatNumber(batch.saleCount) },
      { label: 'Line items', value: formatNumber(batch.lineCount) },
      rows,
    ]
  }
  const tiles: StatTile[] = [{ label: 'Created', value: formatNumber(batch.created) }]
  if (batch.mode === 'create_or_update') {
    tiles.push({ label: 'Updated', value: formatNumber(batch.updated) }, { label: 'Unchanged', value: formatNumber(batch.unchanged) })
  }
  tiles.push(rows)
  return tiles
}

function Details({
  batch,
  timeZone,
  href,
  onFiltersChange,
}: {
  batch: ImportDetail
  timeZone: string
  href: (path: string) => string
  onFiltersChange: PageProps['onFiltersChange']
}) {
  const sales =
    batch.kind === 'sales' && batch.firstSoldAt && batch.lastSoldAt
      ? importedSalesLink(batch.firstSoldAt, batch.lastSoldAt, timeZone, onFiltersChange)
      : null
  const imported = batch.status === 'IMPORTED'
  return (
    <>
      <dl className="imports-details">
        <div>
          <dt>File</dt>
          <dd className="imports-file-name">{batch.fileName}</dd>
        </div>
        <div>
          <dt>Outcome</dt>
          <dd>
            <ImportStatusBadge status={batch.status} />
          </dd>
        </div>
        <div>
          <dt>Type</dt>
          <dd>{KINDS[batch.kind].label}</dd>
        </div>
        {hasModes(batch.kind) && (
          <div>
            <dt>Mode</dt>
            <dd>{MODE_LABELS[batch.mode]}</dd>
          </div>
        )}
        <div>
          <dt>{imported ? 'Imported' : 'Attempted'}</dt>
          <dd>{formatDateTimeLong(batch.createdAt, timeZone)}</dd>
        </div>
        <div>
          <dt>By</dt>
          <dd>{batch.importedBy ?? <span className="text-muted">Unknown</span>}</dd>
        </div>
        {batch.kind === 'sales' && imported && (
          <>
            <div>
              <dt>First sale</dt>
              <dd>{batch.firstSoldAt ? formatDateTimeLong(batch.firstSoldAt, timeZone) : '–'}</dd>
            </div>
            <div>
              <dt>Last sale</dt>
              <dd>{batch.lastSoldAt ? formatDateTimeLong(batch.lastSoldAt, timeZone) : '–'}</dd>
            </div>
          </>
        )}
      </dl>
      {!imported && (
        <p className="imports-hint imports-details-note">
          The file had {batch.errorCount === 1 ? 'an error' : 'errors'}, so nothing was written. Errors aren't kept: validate
          the file again on the Import page to see them and download them as CSV.
        </p>
      )}
      <p className="imports-buttons">
        {sales ? (
          <Link className="button button-secondary" href={sales.href} onClick={sales.onClick}>
            View sales in this period
          </Link>
        ) : imported && batch.kind !== 'sales' ? (
          <Link className="button button-secondary" href={href(KINDS[batch.kind].listPath)}>
            Go to {KINDS[batch.kind].label}
          </Link>
        ) : !imported ? (
          <Link className="button button-secondary" href={href(`/imports${batch.kind === 'sales' ? '' : `?type=${batch.kind}`}`)}>
            Import {KINDS[batch.kind].label.toLowerCase()} again
          </Link>
        ) : null}
      </p>
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
