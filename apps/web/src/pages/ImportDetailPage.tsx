import { PageHeader } from '../components/PageHeader'
import { EmptyState, Panel } from '../components/Panel'
import type { PageProps } from './types'

// Placeholder registered by the integrator; owned and replaced by the CSV import feature branch.
export function ImportDetailPage({ context }: PageProps & { importId: number }) {
  return (
    <>
      <PageHeader eyebrow={context.business.name} title="Import" />
      <Panel title="Import">
        <EmptyState message="Import details are being built." />
      </Panel>
    </>
  )
}
