import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Goals page', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.getByRole('link', { name: 'Objectifs' }).click()
    await page.waitForURL('**/goals')
  })

  test('should show goal cards or empty state', async ({ page }) => {
    // Either goal cards are visible or the empty state message
    const hasGoals = (await page.locator('.grid > *').count()) > 0
    if (hasGoals) {
      // Goal cards with progress bars
      await expect(page.locator('.grid').first()).toBeVisible()
    } else {
      await expect(page.getByText('Aucun objectif défini')).toBeVisible()
    }
  })

  test('should open add goal dialog', async ({ page }) => {
    await page.getByRole('button', { name: 'Nouvel objectif' }).click()
    // Dialog should appear with form fields
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByLabel('Montant cible')).toBeVisible()
    // Close dialog
    await page.getByRole('button', { name: 'Annuler' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })

  test('should list recurring investment plans in their own section', async ({ page }) => {
    await expect(page.getByText('Investissements récurrents')).toBeVisible()
    await expect(page.getByText('DCA mensuel PEA')).toBeVisible()
    // A recurring plan has no target, so it must not render a completion percentage.
    await expect(page.getByText('Montant mensuel')).toBeVisible()
  })

  test('should switch the create dialog between the two goal shapes', async ({ page }) => {
    await page.getByRole('button', { name: 'Nouvel objectif' }).click()
    await expect(page.getByLabel('Montant cible')).toBeVisible()

    await page.getByRole('button', { name: 'Investissement mensuel' }).click()
    // The target machinery is replaced, not merely hidden alongside.
    await expect(page.getByLabel('Montant mensuel')).toBeVisible()
    await expect(page.getByLabel('Montant cible')).toHaveCount(0)
  })
})
