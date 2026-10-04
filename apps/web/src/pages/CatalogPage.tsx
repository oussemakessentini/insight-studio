import { useState, type FormEvent } from 'react'
import { catalogApi } from '../api/account'
import { productsApi } from '../api/client'
import type { ProductInfo } from '../api/types'
import type { CreatedStore } from '../api/account'
import { FormError, FormSuccess, SubmitButton, TextField } from '../components/Form'
import { Link } from '../components/Link'
import { PageHeader } from '../components/PageHeader'
import { Panel } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { useTouched } from '../hooks/useTouched'
import { formatCurrency } from '../lib/format'
import { errorMessage, requiredError } from '../lib/validation'
import '../styles/accounts.css'
import type { PageProps } from './types'

/** Catalog setup for OWNER and ADMIN: add stores and products (CSV imports reference both). */
export function CatalogPage({ context, href, refreshContext }: PageProps) {
  const { business, stores } = context
  // Set once, when arriving from "Create a business".
  const [justCreated] = useState(() => new URLSearchParams(window.location.search).get('created') === '1')

  return (
    <>
      <PageHeader
        eyebrow={business.name}
        title="Catalog"
        subtitle={
          <>
            Stores and products for imports and reports
            <span className="page-subtitle-muted">
              {' '}
              · {stores.length} {stores.length === 1 ? 'store' : 'stores'}
            </span>
          </>
        }
      />
      {justCreated && (
        <FormSuccess>
          <strong>{business.name} is ready.</strong> Add its stores and products, then{' '}
          {context.access.canImport ? <Link href={href('/imports')}>import past sales</Link> : 'import past sales'} or invite
          your team from <Link href={href('/settings/members')}>Members</Link>.
        </FormSuccess>
      )}
      <div className="grid grid-halves">
        <NewStore href={href} onCreated={refreshContext} />
        <NewProduct currency={business.currency} href={href} />
      </div>
    </>
  )
}

type StoreField = 'code' | 'name'

function NewStore({ href, onCreated }: { href: (path: string) => string; onCreated: () => void }) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [city, setCity] = useState('')
  const [busy, setBusy] = useState(false)
  // The caught error (not just its text), so a plan-limit refusal can link to the plans.
  const [error, setError] = useState<unknown>(null)
  const [created, setCreated] = useState<CreatedStore | null>(null)
  const touched = useTouched<StoreField>()

  const errors: Record<StoreField, string | null> = {
    code: requiredError(code, 'Enter a store code, e.g. BOS.'),
    name: requiredError(name, 'Enter the store name.'),
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (errors.code || errors.name) return
    setBusy(true)
    setError(null)
    setCreated(null)
    try {
      const store = await catalogApi.createStore({ code: code.trim(), name: name.trim(), city: city.trim() || null })
      setCreated(store ?? { id: 0, code: code.trim(), name: name.trim(), city: city.trim() || null })
      setCode('')
      setName('')
      setCity('')
      touched.reset()
      // The store filter lists every store: include the new one.
      onCreated()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Panel title="Add a store" subtitle="The code identifies the store in CSV imports.">
      <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
        {error != null && <FormError error={error}>{errorMessage(error)}</FormError>}
        {created && (
          <FormSuccess>
            Store {created.code} · {created.name} added.{' '}
            {created.id > 0 && <Link href={href(`/stores/${created.id}`)}>View store</Link>}
          </FormSuccess>
        )}
        <div className="form-columns">
          <TextField
            label="Code"
            name="store-code"
            autoComplete="off"
            spellCheck={false}
            value={code}
            onChange={(v) => setCode(v.toUpperCase())}
            onBlur={(e) => touched.touch('code', e.currentTarget.value)}
            error={touched.shows('code') ? errors.code : null}
            disabled={busy}
          />
          <TextField
            label="City"
            name="store-city"
            autoComplete="off"
            optional
            value={city}
            onChange={setCity}
            disabled={busy}
          />
        </div>
        <TextField
          label="Name"
          name="store-name"
          autoComplete="off"
          value={name}
          onChange={setName}
          onBlur={(e) => touched.touch('name', e.currentTarget.value)}
          error={touched.shows('name') ? errors.name : null}
          disabled={busy}
        />
        <div>
          <SubmitButton busy={busy} busyLabel="Adding…">
            Add store
          </SubmitButton>
        </div>
      </form>
    </Panel>
  )
}

type ProductField = 'sku' | 'name' | 'category' | 'listPrice'

function priceError(value: string): string | null {
  if (!value.trim()) return 'Enter the list price.'
  const price = Number(value)
  if (!Number.isFinite(price) || price <= 0) return 'Enter a price above zero, e.g. 49.90.'
  if (!/^\d+(\.\d{1,2})?$/.test(value.trim())) return 'Use at most two decimals.'
  return null
}

function NewProduct({ currency, href }: { currency: string; href: (path: string) => string }) {
  const [sku, setSku] = useState('')
  const [name, setName] = useState('')
  const [category, setCategory] = useState('')
  const [listPrice, setListPrice] = useState('')
  const [busy, setBusy] = useState(false)
  // The caught error (not just its text), so a plan-limit refusal can link to the plans.
  const [error, setError] = useState<unknown>(null)
  const [created, setCreated] = useState<ProductInfo | null>(null)
  const [version, setVersion] = useState(0)
  const touched = useTouched<ProductField>()
  // Existing categories as suggestions; a new one can be typed.
  const categories = useApi(`categories|${version}`, (signal) => productsApi.categories(signal))

  const errors: Record<ProductField, string | null> = {
    sku: requiredError(sku, 'Enter a SKU.'),
    name: requiredError(name, 'Enter the product name.'),
    category: requiredError(category, 'Enter a category, e.g. Outerwear.'),
    listPrice: priceError(listPrice),
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (Object.values(errors).some(Boolean)) return
    setBusy(true)
    setError(null)
    setCreated(null)
    const product = { sku: sku.trim(), name: name.trim(), category: category.trim(), listPrice: Number(listPrice) }
    try {
      const saved = await catalogApi.createProduct(product)
      setCreated(saved ?? { id: 0, ...product })
      setSku('')
      setName('')
      setListPrice('')
      // Keep the category: products are often added a category at a time.
      touched.reset()
      setVersion((v) => v + 1)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Panel title="Add a product" subtitle="The SKU identifies the product in CSV imports.">
      <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
        {error != null && <FormError error={error}>{errorMessage(error)}</FormError>}
        {created && (
          <FormSuccess>
            {created.sku} · {created.name} added at {formatCurrency(created.listPrice, currency)}.{' '}
            {created.id > 0 && <Link href={href(`/products/${created.id}`)}>View product</Link>}
          </FormSuccess>
        )}
        <div className="form-columns">
          <TextField
            label="SKU"
            name="product-sku"
            autoComplete="off"
            spellCheck={false}
            value={sku}
            onChange={setSku}
            onBlur={(e) => touched.touch('sku', e.currentTarget.value)}
            error={touched.shows('sku') ? errors.sku : null}
            disabled={busy}
          />
          <TextField
            label={`List price (${currency})`}
            name="product-price"
            autoComplete="off"
            inputMode="decimal"
            value={listPrice}
            onChange={setListPrice}
            onBlur={(e) => touched.touch('listPrice', e.currentTarget.value)}
            error={touched.shows('listPrice') ? errors.listPrice : null}
            disabled={busy}
          />
        </div>
        <TextField
          label="Name"
          name="product-name"
          autoComplete="off"
          value={name}
          onChange={setName}
          onBlur={(e) => touched.touch('name', e.currentTarget.value)}
          error={touched.shows('name') ? errors.name : null}
          disabled={busy}
        />
        <TextField
          label="Category"
          name="product-category"
          autoComplete="off"
          list="catalog-categories"
          value={category}
          onChange={setCategory}
          onBlur={(e) => touched.touch('category', e.currentTarget.value)}
          error={touched.shows('category') ? errors.category : null}
          hint={categories.data?.categories.length ? 'Pick an existing category or type a new one.' : undefined}
          disabled={busy}
        />
        <datalist id="catalog-categories">
          {categories.data?.categories.map((c) => (
            <option key={c} value={c} />
          ))}
        </datalist>
        <div>
          <SubmitButton busy={busy} busyLabel="Adding…">
            Add product
          </SubmitButton>
        </div>
      </form>
    </Panel>
  )
}
