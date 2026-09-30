import { storesApi } from '../api/stores'
import { FilterBar } from '../components/FilterBar'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, Panel, SkeletonRows } from '../components/Panel'
import { StoreTable } from '../components/stores/StoreTable'
import { useApi } from '../hooks/useApi'
import { formatDateRange, formatNumber } from '../lib/format'
import '../styles/stores.css'
import type { PageProps } from './types'

/** Every store's performance for the selected dates. The global store filter does not apply. */
export function StoresPage({ context, filters, onFiltersChange, href }: PageProps) {
  const { business, stores, dataRange } = context
  const dates = { from: filters.from, to: filters.to }
  const list = useApi(`stores-list|${filters.from}|${filters.to}`, (signal) => storesApi.list(dates, signal))
  const count = list.data?.stores.length ?? 0

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Stores"
        subtitle={
          <>
            All stores · {formatDateRange(filters.from, filters.to)}
            {list.data && (
              <span className="page-subtitle-muted">
                {' '}
                · Compared with {formatDateRange(list.data.previousPeriod.from, list.data.previousPeriod.to)}
              </span>
            )}
          </>
        }
      >
        <FilterBar filters={filters} stores={stores} dataRange={dataRange} onChange={onFiltersChange} showStore={false} />
      </PageHeader>

      <Panel
        title="Store performance"
        subtitle={list.data ? `${formatNumber(count)} ${count === 1 ? 'store' : 'stores'} · ranked by revenue` : undefined}
      >
        <AsyncContent
          {...list}
          isEmpty={(d) => d.stores.length === 0}
          emptyMessage="This business has no stores yet."
          skeleton={<SkeletonRows rows={4} />}
        >
          {(data) => <StoreTable data={data} currency={business.currency} storeHref={(id) => href(`/stores/${id}`)} />}
        </AsyncContent>
      </Panel>
    </>
  )
}
