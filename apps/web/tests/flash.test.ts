import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { businessDeletedMessage, dismissFlash, flash } from '../src/lib/flash.ts'

describe('businessDeletedMessage', () => {
  it('names the deleted business and the one the app switched to', () => {
    assert.equal(businessDeletedMessage('Doomed Co', 'Fieldstone'), '“Doomed Co” was deleted. You\'re now working in “Fieldstone”.')
  })
  it('names only the deleted business when none is left', () => {
    assert.equal(businessDeletedMessage('Doomed Co', null), '“Doomed Co” was deleted.')
  })
})

describe('flash', () => {
  it('can be set and dismissed without a React tree', () => {
    flash('Saved')
    dismissFlash()
    dismissFlash(42) // no-op
  })
})
