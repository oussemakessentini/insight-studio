import { useState } from 'react'
import { dashboardApi } from './api/client'
import { LogoMark, MenuIcon } from './components/Icons'
import { ErrorState, Skeleton } from './components/Panel'
import { Sidebar } from './components/Sidebar'
import { useApi } from './hooks/useApi'
import { DashboardPage } from './pages/DashboardPage'

function App() {
  const [menuOpen, setMenuOpen] = useState(false)
  const context = useApi('context', (signal) => dashboardApi.context(signal))

  return (
    <div className="app-shell">
      <Sidebar businessName={context.data?.business.name} open={menuOpen} onClose={() => setMenuOpen(false)} />

      <div className="app-main">
        <div className="topbar">
          <button
            type="button"
            className="icon-button"
            onClick={() => setMenuOpen(true)}
            aria-label="Open menu"
            aria-expanded={menuOpen}
            aria-controls="sidebar"
          >
            <MenuIcon />
          </button>
          <LogoMark width={24} height={24} />
          <span className="topbar-title">Insight Studio</span>
        </div>

        <main className="content">
          {context.error ? (
            <div className="panel page-error">
              <h1 className="page-title">Dashboard unavailable</h1>
              <ErrorState message={context.error.message} onRetry={context.retry} />
            </div>
          ) : context.data ? (
            <DashboardPage context={context.data} />
          ) : (
            <PageSkeleton />
          )}
        </main>
      </div>
    </div>
  )
}

function PageSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading dashboard">
      <div className="page-header">
        <div>
          <Skeleton height={14} width={160} />
          <Skeleton height={30} width={240} />
        </div>
      </div>
      <div className="metric-grid">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="metric-card">
            <Skeleton height={14} width="50%" />
            <Skeleton height={32} width="70%" />
          </div>
        ))}
      </div>
    </div>
  )
}

export default App
