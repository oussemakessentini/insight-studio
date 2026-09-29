import { PageHeader } from '../components/PageHeader'
import { EmptyState, Panel } from '../components/Panel'
import type { PageProps } from './types'

// Placeholder registered by the integrator; owned and replaced by the Stores feature branch.
export function StoreDetailPage({ context }: PageProps & { storeId: number }) {
  return (
    <>
      <PageHeader eyebrow={context.business.name} title="Store" />
      <Panel title="Store">
        <EmptyState message="Store details are being built." />
      </Panel>
    </>
  )
}
