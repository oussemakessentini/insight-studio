import type { CSSProperties, ReactNode } from 'react'
import type { LayoutGrid, LayoutItem } from '../../api/dashboards'
import { byPosition, cellStyle, gridBottom } from '../../lib/dashboardLayout'

/**
 * A dashboard's widgets on one of its grids (12 or 4 columns, 80 px rows). Items are rendered in
 * reading order, so keyboard and screen-reader order follows what is on screen.
 */
export function DashboardGrid({ grid, children }: { grid: LayoutGrid; children: (item: LayoutItem) => ReactNode }) {
  const style = { '--grid-columns': grid.columns, '--grid-rows': gridBottom(grid.items) } as CSSProperties
  return (
    <div className={`dashboard-grid dashboard-grid-${grid.columns === 12 ? 'desktop' : 'mobile'}`} style={style}>
      {byPosition(grid.items).map((item) => (
        <div key={item.id} className="dashboard-cell" style={cellStyle(item)}>
          {children(item)}
        </div>
      ))}
    </div>
  )
}
