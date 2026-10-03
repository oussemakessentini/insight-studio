import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  businessNameConfirmed,
  businessNameError,
  emailConfirmed,
  filterTimeZones,
  settingsChange,
  timeZoneLabel,
} from '../src/lib/accountSettings.ts'

describe('typed confirmations', () => {
  it('business name: exact after trimming, case sensitive', () => {
    assert.equal(businessNameConfirmed('Corner Shop', 'Corner Shop'), true)
    assert.equal(businessNameConfirmed('  Corner Shop ', 'Corner Shop'), true)
    assert.equal(businessNameConfirmed('corner shop', 'Corner Shop'), false)
    assert.equal(businessNameConfirmed('Corner', 'Corner Shop'), false)
    assert.equal(businessNameConfirmed('', ''), false)
  })
  it('email: case ignored', () => {
    assert.equal(emailConfirmed('Ana@Example.com ', 'ana@example.com'), true)
    assert.equal(emailConfirmed('ana@example.co', 'ana@example.com'), false)
    assert.equal(emailConfirmed(' ', ''), false)
  })
})

describe('filterTimeZones', () => {
  const zones = ['America/New_York', 'America/Argentina/Buenos_Aires', 'Europe/Paris', 'UTC']
  it('matches every word, spaces or underscores', () => {
    assert.deepEqual(filterTimeZones(zones, 'new york'), ['America/New_York'])
    assert.deepEqual(filterTimeZones(zones, 'BUENOS_aires'), ['America/Argentina/Buenos_Aires'])
    assert.deepEqual(filterTimeZones(zones, 'america'), ['America/New_York', 'America/Argentina/Buenos_Aires'])
  })
  it('returns everything for an empty search and keeps the selection', () => {
    assert.equal(filterTimeZones(zones, '  ').length, 4)
    assert.deepEqual(filterTimeZones(zones, 'paris', 'UTC'), ['UTC', 'Europe/Paris'])
  })
  it('labels zones readably', () => assert.equal(timeZoneLabel('America/New_York'), 'America / New York'))
})

describe('settingsChange', () => {
  const saved = { name: 'Shop', timeZone: 'UTC', currency: 'USD' }
  it('sends only changed fields', () => {
    assert.deepEqual(settingsChange(saved, { ...saved, name: ' Shop ' }), {})
    assert.deepEqual(settingsChange(saved, { ...saved, name: 'Store', timeZone: 'Europe/Paris' }), {
      name: 'Store',
      timeZone: 'Europe/Paris',
    })
    assert.deepEqual(settingsChange(saved, { ...saved, currency: 'EUR' }), { currency: 'EUR' })
  })
})

describe('businessNameError', () => {
  it('mirrors the server rules', () => {
    assert.equal(businessNameError('  '), 'Enter the business name.')
    assert.equal(businessNameError('x'.repeat(201)), 'Use at most 200 characters.')
    assert.equal(businessNameError('a\tb'), "The name can't contain control characters.")
    assert.equal(businessNameError('Shop'), null)
  })
})
