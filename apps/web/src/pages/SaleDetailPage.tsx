import { ApiError, salesApi } from '../api/client'
import type { SaleDetail } from '../api/types'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, EmptyState, ErrorState, Panel, Skeleton, SkeletonRows } from '../components/Panel'
import { PriceComparison } from '../components/PriceComparison'
import { StatTiles } from '../components/StatTiles'
import { useApi } from '../hooks/useApi'
import { formatCurrency, formatDateTimeLong, formatNumber } from '../lib/format'
import type { PageProps } from './types'

/**
 * One receipt. The shared date/store filters don't change what a single sale contains, so there
 * is no filter bar here; they stay in the URL so links back to lists keep them.
 */
export function SaleDetailPage({ saleId, context, href }: PageProps & { saleId: number }) {
  const { business } = context
  const sale = useApi(`sale|${saleId}`, (signal) => salesApi.detail(saleId, signal))

  if (sale.error instanceof ApiError && sale.error.status === 404) {
    return <SaleNotFound salesHref={href('/sales')} />
  }

  const data = sale.data
  const money = (v: number) => formatCurrency(v, business.currency)

  return (
    <>
      <PageHeader
        eyebrow={
          <nav aria-label="Breadcrumb" className="breadcrumb">
            <Link href={href('/sales')}>Sales</Link>
            <span aria-hidden="true">/</span>
            <span>Receipt</span>
          </nav>
        }
        title={data ? <span className="mono-title">{data.receiptNumber}</span> : <Skeleton height={30} width={260} />}
        subtitle={
          data ? (
            <>
              {formatDateTimeLong(data.soldAt, business.timeZone)} · {data.store.name}
              {data.store.city && <span className="page-subtitle-muted"> · {data.store.city}</span>}
            </>
          ) : undefined
        }
      />

      {sale.error ? (
        <div className="panel">
          <ErrorState message={sale.error.message} onRetry={sale.retry} />
        </div>
      ) : data ? (
        <StatTiles
          tiles={[
            { label: 'Total', value: money(data.total), caption: 'At the prices charged' },
            { label: 'Items', value: formatNumber(data.lineCount), caption: 'Distinct products' },
            { label: 'Units', value: formatNumber(data.unitCount) },
            { label: 'Store', value: data.store.name, caption: data.store.city ?? 'Online' },
          ]}
        />
      ) : (
        <StatTilesSkeleton />
      )}

      <Panel title="Line items" subtitle="Unit prices are those charged at the time of sale">
        <AsyncContent {...sale} skeleton={<SkeletonRows rows={3} />}>
          {(d) =>
            d.lines.length === 0 ? (
              <EmptyState message="This receipt has no line items, so it is not counted as an order in sales lists, totals or dashboard figures." />
            ) : (
              <LinesTable sale={d} currency={business.currency} productHref={(id) => href(`/products/${id}`)} />
            )
          }
        </AsyncContent>
      </Panel>
    </>
  )
}

function LinesTable({
  sale,
  currency,
  productHref,
}: {
  sale: SaleDetail
  currency: string
  productHref: (id: number) => string
}) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Product</th>
            <th scope="col" className="hide-sm">Category</th>
            <th scope="col" className="num">Qty</th>
            <th scope="col" className="num">Unit price</th>
            <th scope="col" className="num">Line total</th>
          </tr>
        </thead>
        <tbody>
          {sale.lines.map((l) => (
            <tr key={l.productId}>
              <td>
                <Link className="cell-primary cell-link" href={productHref(l.productId)}>
                  {l.name}
                </Link>
                <span className="cell-secondary">{l.sku}</span>
              </td>
              <td className="hide-sm">
                <span className="chip">{l.category}</span>
              </td>
              <td className="num">{formatNumber(l.quantity)}</td>
              <td className="num">
                {formatCurrency(l.unitPrice, currency)}
                <PriceComparison price={l.unitPrice} listPrice={l.currentListPrice} />
              </td>
              <td className="num strong">{formatCurrency(l.lineTotal, currency)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan={2} className="hide-sm">
              Total
            </th>
            <th scope="row" className="show-sm-cell">
              Total
            </th>
            <td className="num">{formatNumber(sale.unitCount)}</td>
            <td className="num" />
            <td className="num strong">{formatCurrency(sale.total, currency)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}

function StatTilesSkeleton() {
  return (
    <div className="metric-grid" aria-busy="true">
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="metric-card">
          <Skeleton height={14} width="50%" />
          <Skeleton height={32} width="70%" />
        </div>
      ))}
    </div>
  )
}

function SaleNotFound({ salesHref }: { salesHref: string }) {
  return (
    <div className="panel page-error">
      <h1 className="page-title">Sale not found</h1>
      <ErrorState message="This receipt does not exist or belongs to another business." />
      <p className="page-error-action">
        <Link className="button button-secondary" href={salesHref}>
          Back to sales
        </Link>
      </p>
    </div>
  )
}
