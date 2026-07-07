import { createBrowserRouter } from 'react-router-dom'
import { RequireAuth, PublicOnly, RequireAdmin } from '@/features/auth/guards'
import { RequireSetup, SetupOnly } from '@/features/setup/guards'
import { AppLayout } from '@/components/layout/AppLayout'
import '@/pages/setup/setup.css'
import {
  LoginPage,
  MfaChallengePage,
  DashboardPage,
  AccountsPage,
  AccountDetailPage,
  GoalsPage,
  GoalCalendarPage,
  BudgetLayout,
  BudgetOverviewPage,
  SpendingPage,
  CategoryDetailPage,
  SubscriptionsPage,
  EnvelopesPage,
  ReviewPage,
  BudgetSettingsPage,
  BudgetTransactionsPage,
  SyncPage,
  SettingsPage,
  ActivationPage,
  FamilyDashboardPage,
  FamilySettingsPage,
  AdminPage,
  SetupLayout,
  SetupStepIntro,
  SetupStepAdmin,
  SetupStepSecurity,
  SetupStepIntegrations,
  SetupStepComplete,
  SetupStepEnableBanking,
  SetupStepBoursoBank,
  SetupStepTradeRepublic,
  SetupStepRevolut,
  SetupStepFinary,
  SetupStepCrypto,
  NotFoundPage,
  ForbiddenPage,
  ServerErrorPage,
  SuspensePage,
} from './lazy-pages'

export const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <PublicOnly>
        <SuspensePage>
          <LoginPage />
        </SuspensePage>
      </PublicOnly>
    ),
  },
  {
    // /login/mfa is also for unauthenticated visitors — the user is mid-login
    // (mfa_challenge cookie set, access_token NOT yet set), so PublicOnly applies.
    path: '/login/mfa',
    element: (
      <PublicOnly>
        <SuspensePage>
          <MfaChallengePage />
        </SuspensePage>
      </PublicOnly>
    ),
  },
  {
    path: '/error/403',
    element: (
      <SuspensePage>
        <ForbiddenPage />
      </SuspensePage>
    ),
  },
  {
    path: '/error/500',
    element: (
      <SuspensePage>
        <ServerErrorPage />
      </SuspensePage>
    ),
  },
  {
    path: '/setup',
    element: (
      <SetupOnly>
        <SuspensePage fallback={null}>
          <SetupLayout />
        </SuspensePage>
      </SetupOnly>
    ),
    children: [
      { index: true, element: <SuspensePage fallback={null}><SetupStepIntro /></SuspensePage> },
      { path: 'admin', element: <SuspensePage fallback={null}><SetupStepAdmin /></SuspensePage> },
      { path: 'security', element: <SuspensePage fallback={null}><SetupStepSecurity /></SuspensePage> },
      { path: 'integrations', element: <SuspensePage fallback={null}><SetupStepIntegrations /></SuspensePage> },
      { path: 'integrations/enablebanking', element: <SuspensePage fallback={null}><SetupStepEnableBanking /></SuspensePage> },
      { path: 'integrations/boursobank', element: <SuspensePage fallback={null}><SetupStepBoursoBank /></SuspensePage> },
      { path: 'integrations/traderepublic', element: <SuspensePage fallback={null}><SetupStepTradeRepublic /></SuspensePage> },
      { path: 'integrations/revolut', element: <SuspensePage fallback={null}><SetupStepRevolut /></SuspensePage> },
      { path: 'integrations/finary', element: <SuspensePage fallback={null}><SetupStepFinary /></SuspensePage> },
      { path: 'integrations/crypto', element: <SuspensePage fallback={null}><SetupStepCrypto /></SuspensePage> },
      { path: 'done', element: <SuspensePage fallback={null}><SetupStepComplete /></SuspensePage> },
    ],
  },
  {
    path: '/',
    element: (
      <RequireSetup>
        <RequireAuth>
          <AppLayout />
        </RequireAuth>
      </RequireSetup>
    ),
    children: [
      { index: true, element: <SuspensePage><DashboardPage /></SuspensePage> },
      { path: 'accounts', element: <SuspensePage><AccountsPage /></SuspensePage> },
      { path: 'accounts/:id', element: <SuspensePage><AccountDetailPage /></SuspensePage> },
      { path: 'goals', element: <SuspensePage><GoalsPage /></SuspensePage> },
      { path: 'goals/:id/calendar', element: <SuspensePage><GoalCalendarPage /></SuspensePage> },
      {
        path: 'budget',
        element: <SuspensePage><BudgetLayout /></SuspensePage>,
        children: [
          { index: true, element: <SuspensePage><BudgetOverviewPage /></SuspensePage> },
          { path: 'spending', element: <SuspensePage><SpendingPage /></SuspensePage> },
          { path: 'spending/:categoryId', element: <SuspensePage><CategoryDetailPage /></SuspensePage> },
          { path: 'transactions', element: <SuspensePage><BudgetTransactionsPage /></SuspensePage> },
          { path: 'subscriptions', element: <SuspensePage><SubscriptionsPage /></SuspensePage> },
          { path: 'envelopes', element: <SuspensePage><EnvelopesPage /></SuspensePage> },
          { path: 'review', element: <SuspensePage><ReviewPage /></SuspensePage> },
          { path: 'settings', element: <SuspensePage><BudgetSettingsPage /></SuspensePage> },
        ],
      },
      { path: 'sync', element: <SuspensePage><SyncPage /></SuspensePage> },
      { path: 'sync/callback', element: <SuspensePage><SyncPage /></SuspensePage> },
      { path: 'settings', element: <SuspensePage><SettingsPage /></SuspensePage> },
      { path: 'family', element: <SuspensePage><FamilyDashboardPage /></SuspensePage> },
      { path: 'settings/family', element: <SuspensePage><FamilySettingsPage /></SuspensePage> },
      { path: 'admin', element: <SuspensePage><RequireAdmin><AdminPage /></RequireAdmin></SuspensePage> },
    ],
  },
  {
    path: '/activate/:token',
    element: (
      <SuspensePage>
        <ActivationPage />
      </SuspensePage>
    ),
  },
  {
    path: '*',
    element: (
      <SuspensePage>
        <NotFoundPage />
      </SuspensePage>
    ),
  },
])
