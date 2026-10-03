import { useId, useState } from 'react'
import { getJson } from '../../api/client'
import type { ProductListResponse } from '../../api/types'
import { useApi } from '../../hooks/useApi'
import { useDebounced } from '../../hooks/useDebounced'
import { useProductNames } from '../../hooks/useProductNames'
import { CloseIcon } from '../Icons'

const RESULTS = 8

interface ProductPickerProps {
  selected: number[]
  onChange: (ids: number[]) => void
  max: number
  disabled?: boolean
  error?: string | null
}

/**
 * Products to filter on, found with the catalogue search (`/api/products?q=`): matching products
 * can be added, chosen ones show as removable chips.
 */
export function ProductPicker({ selected, onChange, max, disabled = false, error }: ProductPickerProps) {
  const inputId = useId()
  const [query, setQuery] = useState('')
  // Names learned from search results, so a product added just now doesn't need another request.
  const [learned, setLearned] = useState<ReadonlyMap<number, string>>(new Map())
  const saved = useProductNames(selected.filter((id) => !learned.has(id)))
  const search = useDebounced(query.trim(), 250)
  const results = useApi(`chart-product-search|${search}`, (signal) =>
    search
      ? getJson<ProductListResponse>('/api/products', { q: search, sort: 'name', direction: 'asc', size: RESULTS }, signal)
      : Promise.resolve(null),
  )
  const nameOf = (id: number) => learned.get(id) ?? saved.get(id) ?? `Product ${id}`
  const full = selected.length >= max

  const add = (id: number, name: string) => {
    if (selected.includes(id) || full) return
    setLearned((prev) => new Map(prev).set(id, name))
    onChange([...selected, id])
  }

  return (
    <div className="chart-filter">
      <label className="form-label" htmlFor={inputId}>
        Products
      </label>
      {selected.length > 0 ? (
        <ul className="filter-chips chart-chips" aria-label="Chosen products">
          {selected.map((id) => (
            <li key={id} className="filter-chip">
              <span className="break-anywhere">{nameOf(id)}</span>
              <button
                type="button"
                className="filter-chip-remove"
                onClick={() => onChange(selected.filter((p) => p !== id))}
                disabled={disabled}
                aria-label={`Remove ${nameOf(id)}`}
              >
                <CloseIcon width={14} height={14} />
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="form-hint">All products. Search to narrow the chart to some.</p>
      )}
      <input
        id={inputId}
        type="search"
        className="control form-control"
        placeholder="Search by name or SKU"
        value={query}
        maxLength={100}
        autoComplete="off"
        disabled={disabled || full}
        aria-describedby={`${inputId}-status`}
        aria-invalid={error ? true : undefined}
        onChange={(e) => setQuery(e.target.value)}
      />
      <p className="form-hint" id={`${inputId}-status`} aria-live="polite">
        {full
          ? `At most ${max} products. Remove one to add another.`
          : !search
            ? null
            : results.error
              ? results.error.message
              : results.data && results.data.items.length === 0
                ? `No products match “${search}”.`
                : null}
      </p>
      {error && <p className="form-error">{error}</p>}
      {search && results.data && results.data.items.length > 0 && !full && (
        <ul className={`chart-product-results ${results.loading ? 'is-refreshing' : ''}`} aria-label="Matching products">
          {results.data.items.map((product) => {
            const chosen = selected.includes(product.productId)
            return (
              <li key={product.productId}>
                <button
                  type="button"
                  className="chart-product-result"
                  disabled={disabled || chosen}
                  onClick={() => add(product.productId, product.name)}
                >
                  <span className="chart-product-name break-anywhere">{product.name}</span>
                  <span className="cell-secondary">
                    {product.sku} · {product.category}
                  </span>
                  <span className="chart-product-action">{chosen ? 'Added' : 'Add'}</span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
