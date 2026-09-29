import type { ReactNode } from 'react'

export interface StatTile {
  label: string
  value: ReactNode
  caption?: ReactNode
}

/** Plain figures in the metric-card style, without period comparisons. */
export function StatTiles({ tiles }: { tiles: StatTile[] }) {
  return (
    <div className="metric-grid">
      {tiles.map((t) => (
        <article key={t.label} className="metric-card">
          <h3 className="metric-label">{t.label}</h3>
          <p className="metric-value">{t.value}</p>
          {t.caption && <p className="metric-caption">{t.caption}</p>}
        </article>
      ))}
    </div>
  )
}
