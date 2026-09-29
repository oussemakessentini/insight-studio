import { PageHeader } from '../components/PageHeader'
import { EmptyState, Panel } from '../components/Panel'
import type { PageProps } from './types'

// Placeholder registered by the integrator; owned and replaced by the CSV import feature branch.
export function ImportsPage({ context }: PageProps) {
  return (
    <>
      <PageHeader eyebrow={context.business.name} title="Import sales" />
      <Panel title="Import sales">
        <EmptyState message="CSV import is being built." />
      </Panel>
    </>
  )
}
