import type { KeyboardEvent } from 'react'
import type { ReportKind } from '../../api/reports'
import { REPORT_TABS, panelId, tabId } from './tabs'

interface ReportTabsProps {
  value: ReportKind
  onChange: (value: ReportKind) => void
}

/** WAI-ARIA tabs: arrow keys, Home and End move between reports. */
export function ReportTabs({ value, onChange }: ReportTabsProps) {
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const index = REPORT_TABS.findIndex((t) => t.value === value)
    const last = REPORT_TABS.length - 1
    const next =
      event.key === 'ArrowRight' ? (index === last ? 0 : index + 1)
      : event.key === 'ArrowLeft' ? (index === 0 ? last : index - 1)
      : event.key === 'Home' ? 0
      : event.key === 'End' ? last
      : null
    if (next === null) return
    event.preventDefault()
    const kind = REPORT_TABS[next].value
    onChange(kind)
    document.getElementById(tabId(kind))?.focus()
  }

  return (
    <div className="reports-tabs" role="tablist" aria-label="Report">
      {REPORT_TABS.map((tab) => {
        const selected = tab.value === value
        return (
          <button
            key={tab.value}
            id={tabId(tab.value)}
            type="button"
            role="tab"
            className={`reports-tab ${selected ? 'is-selected' : ''}`}
            aria-selected={selected}
            aria-controls={panelId(tab.value)}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(tab.value)}
            onKeyDown={onKeyDown}
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}
