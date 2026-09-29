import { formatPercent } from '../lib/format'

/** Below this difference a price is shown as matching the list price. */
const TOLERANCE_PERCENT = 0.5

/** "−30.0% vs list" when a price charged differs meaningfully from the current list price. */
export function PriceComparison({ price, listPrice }: { price: number; listPrice: number }) {
  if (listPrice <= 0) return null
  const diff = ((price - listPrice) / listPrice) * 100
  if (Math.abs(diff) < TOLERANCE_PERCENT) return null
  return (
    <span className="price-diff" title="Compared with the current list price">
      {formatPercent(diff, { signed: true })} vs list
    </span>
  )
}
