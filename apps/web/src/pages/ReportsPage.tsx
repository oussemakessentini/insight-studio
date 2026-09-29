import { PageHeader } from '../components/PageHeader'
import { EmptyState, Panel } from '../components/Panel'
import type { PageProps } from './types'

// Placeholder registered by the integrator; owned and replaced by the Reports feature branch.
export function ReportsPage({ context }: PageProps) {
  return (
    <>
      <PageHeader eyebrow={context.business.name} title="Reports" />
      <Panel title="Reports">
        <EmptyState message="Reports are being built." />
      </Panel>
    </>
  )
}
