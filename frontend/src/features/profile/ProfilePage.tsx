import { useEffect, useState, type FormEvent } from 'react'
import Icon from '../../components/Icon'
import SignInRequired from '../../components/ui/SignInRequired'
import { ErrorState, LoadingBlock, describeError } from '../../components/ui/QueryStates'
import { useGetMeQuery, useUpdateMeMutation } from './profileApi'
import { isAuthenticated } from '../../auth'

const CURRENCIES = ['EUR', 'GBP', 'CHF', 'USD']
const field = 'w-full p-md bg-surface-container-low border border-outline-variant rounded-lg focus:ring-2 focus:ring-primary focus:outline-none transition-all'
const label = 'font-label-caps text-label-caps text-on-surface-variant'

export default function ProfilePage() {
  const authed = isAuthenticated()
  const { data: profile, isLoading, isError, error, refetch } = useGetMeQuery(undefined, { skip: !authed })
  const [updateMe, updateState] = useUpdateMeMutation()

  const [form, setForm] = useState({ givenName: '', familyName: '', preferredUsername: '', locale: '', preferredCurrency: '' })
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (profile) {
      setForm({
        givenName: profile.givenName ?? '',
        familyName: profile.familyName ?? '',
        preferredUsername: profile.preferredUsername ?? '',
        locale: profile.locale ?? '',
        preferredCurrency: profile.preferredCurrency ?? '',
      })
    }
  }, [profile])

  if (!authed) return <SignInRequired message="Sign in to view and edit your travel profile." />

  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => {
    setForm((f) => ({ ...f, [k]: e.target.value }))
    setSaved(false)
  }

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    try {
      await updateMe({
        givenName: form.givenName || null,
        familyName: form.familyName || null,
        preferredUsername: form.preferredUsername || null,
        locale: form.locale || null,
        preferredCurrency: form.preferredCurrency || null,
      }).unwrap()
      setSaved(true)
    } catch {
      // surfaced below via updateState.error
    }
  }

  return (
    <main className="flex-grow w-full max-w-2xl mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <h1 className="font-display-lg text-display-lg-mobile mb-lg">Profile</h1>

      {isLoading && <LoadingBlock label="Loading your profile…" />}
      {isError && <ErrorState error={error} onRetry={refetch} />}

      {profile && (
        <div className="bg-surface-container-lowest border border-outline-variant/10 rounded-xl p-xl shadow-sm">
          <div className="flex items-center gap-md mb-lg pb-lg border-b border-outline-variant/20">
            <div className="w-14 h-14 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center">
              <Icon name="account_circle" className="text-3xl" />
            </div>
            <div>
              <p className="font-title-lg text-title-lg">{profile.email}</p>
              <p className="font-body-sm text-body-sm text-on-surface-variant">Identity managed by Keycloak</p>
            </div>
          </div>

          <form className="flex flex-col gap-lg" onSubmit={onSubmit}>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-md">
              <div className="flex flex-col gap-sm">
                <label className={label} htmlFor="givenName">GIVEN NAME</label>
                <input id="givenName" className={field} value={form.givenName} onChange={set('givenName')} />
              </div>
              <div className="flex flex-col gap-sm">
                <label className={label} htmlFor="familyName">FAMILY NAME</label>
                <input id="familyName" className={field} value={form.familyName} onChange={set('familyName')} />
              </div>
            </div>
            <div className="flex flex-col gap-sm">
              <label className={label} htmlFor="preferredUsername">PREFERRED USERNAME</label>
              <input id="preferredUsername" className={field} value={form.preferredUsername} onChange={set('preferredUsername')} />
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-md">
              <div className="flex flex-col gap-sm">
                <label className={label} htmlFor="locale">LOCALE</label>
                <input id="locale" className={field} value={form.locale} onChange={set('locale')} placeholder="en-GB" />
              </div>
              <div className="flex flex-col gap-sm">
                <label className={label} htmlFor="preferredCurrency">PREFERRED CURRENCY</label>
                <select id="preferredCurrency" className={field} value={form.preferredCurrency} onChange={set('preferredCurrency')}>
                  <option value="">—</option>
                  {CURRENCIES.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </div>
            </div>

            {updateState.isError && (
              <p className="font-body-sm text-body-sm text-error" role="alert">{describeError(updateState.error)}</p>
            )}
            <div className="flex items-center gap-md">
              <button
                type="submit"
                disabled={updateState.isLoading}
                className="px-lg py-md bg-accent text-on-background rounded-lg font-bold hover:brightness-110 active:scale-95 transition-all shadow-md disabled:opacity-60 disabled:pointer-events-none flex items-center gap-sm"
              >
                {updateState.isLoading && <Icon name="progress_activity" className="animate-spin" />}
                Save changes
              </button>
              {saved && !updateState.isLoading && (
                <span className="flex items-center gap-xs text-primary font-body-sm text-body-sm">
                  <Icon name="check_circle" filled /> Saved
                </span>
              )}
            </div>
          </form>
        </div>
      )}
    </main>
  )
}
