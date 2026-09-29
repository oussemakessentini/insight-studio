import { PageHeader } from '../components/PageHeader'
import { EmptyState, Panel } from '../components/Panel'
import type { PageProps } from './types'

// Placeholder registered by the integrator; owned and replaced by the Stores feature branch.
export function StoresPage({ context }: PageProps) {
  return (
    <>
      <PageHeader eyebrow={context.business.name} title="Stores" />
      <Panel title="Stores">
        <EmptyState message="Store performance is being built." />
      </Panel>
    </>
  )
}
