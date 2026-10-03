import { getJson } from '../api/client'
import type { ProductDetail } from '../api/types'
import { useApi } from './useApi'

/**
 * Names of the products a chart filters on (definitions store ids only). A product that can't be
 * read (e.g. deleted since) shows as "Product {id}".
 */
export function useProductNames(ids: number[]): Map<number, string> {
  const key = ids.join(',')
  const names = useApi(`product-names|${key}`, (signal) =>
    Promise.all(
      ids.map((id) =>
        getJson<ProductDetail>(`/api/products/${id}`, {}, signal)
          .then((detail) => [id, detail.product.name] as const)
          .catch((err: unknown) => {
            if (signal.aborted) throw err
            return [id, `Product ${id}`] as const
          }),
      ),
    ),
  )
  return new Map(names.data ?? [])
}
