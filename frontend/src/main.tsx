import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import ErrorBoundary from './ErrorBoundary.tsx'
import GuardDashboardPage from './components/GuardDashboardPage.tsx'
import MangaAgentPage from './components/MangaAgentPage.tsx'

const Root = window.location.pathname === '/internal/guard'
  ? GuardDashboardPage
  : window.location.pathname === '/internal/manga-agent'
    ? MangaAgentPage
    : App

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <Root />
    </ErrorBoundary>
  </StrictMode>,
)
