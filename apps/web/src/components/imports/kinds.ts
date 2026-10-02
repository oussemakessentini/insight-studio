import { IMPORT_KINDS, type ImportKind, type ImportMode } from '../../api/imports'

export const DEFAULT_KIND: ImportKind = 'sales'

interface KindInfo {
  label: string
  description: string
  /** What one created record is called: "receipt", "store", "product". */
  noun: string
  nounPlural: string
  /** The page that lists what this kind of import adds. */
  listPath: string
}

export const KINDS: Record<ImportKind, KindInfo> = {
  sales: {
    label: 'Sales',
    description: 'Past receipts and their line items, for existing stores and products.',
    noun: 'receipt',
    nounPlural: 'receipts',
    listPath: '/sales',
  },
  stores: {
    label: 'Stores',
    description: 'Store codes, names and cities.',
    noun: 'store',
    nounPlural: 'stores',
    listPath: '/stores',
  },
  products: {
    label: 'Products',
    description: 'SKUs with their name, category and list price.',
    noun: 'product',
    nounPlural: 'products',
    listPath: '/products',
  },
}

export const MODE_LABELS: Record<ImportMode, string> = {
  create_only: 'Create new only',
  create_or_update: 'Create and update existing',
}

/** "1 store", "3 products". */
export function countOf(kind: ImportKind, count: number, format: (n: number) => string): string {
  const info = KINDS[kind]
  return `${format(count)} ${count === 1 ? info.noun : info.nounPlural}`
}

/** Stores and products can update existing records; sales only ever create. */
export function hasModes(kind: ImportKind): boolean {
  return kind !== 'sales'
}

/** The type selected by `?type=`, falling back to sales for a missing or unknown value. */
export function kindFromUrl(): ImportKind {
  const value = new URLSearchParams(window.location.search).get('type')
  return IMPORT_KINDS.find((k) => k === value) ?? DEFAULT_KIND
}
