import { useState, type FormEvent } from 'react'
import { membersApi, ROLE_LABELS, type Member } from '../api/account'
import type { Role } from '../api/types'
import { FormError, FormSuccess, SelectField, SubmitButton, TextField } from '../components/Form'
import { PageHeader } from '../components/PageHeader'
import { AsyncContent, Panel, SkeletonRows } from '../components/Panel'
import { useApi } from '../hooks/useApi'
import { useTouched } from '../hooks/useTouched'
import { useLoadedSession } from '../lib/session'
import { emailError, errorMessage } from '../lib/validation'
import '../styles/accounts.css'
import type { PageProps } from './types'

const ROLE_ORDER: Role[] = ['VIEWER', 'ADMIN', 'OWNER']

const ROLE_HELP: Record<Role, string> = {
  VIEWER: 'Viewers see dashboards, sales and reports.',
  ADMIN: 'Admins also import sales, set up stores and products, and add viewers and admins.',
  OWNER: 'Owners also change roles, remove anyone and manage the business.',
}

/**
 * What the current role may do with members (contract §4). The API enforces the same rules; this
 * only decides which controls to show.
 */
function permissions(myRole: Role | 'DEMO', myUserId: number, members: Member[]) {
  const owners = members.filter((m) => m.role === 'OWNER').length
  const isLastOwner = (m: Member) => m.role === 'OWNER' && owners <= 1
  return {
    addableRoles: myRole === 'OWNER' ? ROLE_ORDER : myRole === 'ADMIN' ? ROLE_ORDER.filter((r) => r !== 'OWNER') : [],
    canChangeRole: (m: Member) => myRole === 'OWNER' && !isLastOwner(m),
    canRemove: (m: Member) => m.userId !== myUserId && (myRole === 'OWNER' || (myRole === 'ADMIN' && m.role === 'VIEWER')),
    canLeave: (m: Member) => m.userId === myUserId && !isLastOwner(m),
    isLastOwner,
  }
}

type Notice = { kind: 'success' | 'error'; message: string } | null

export function MembersPage({ context, refreshContext }: PageProps) {
  const { session, businessId, reload } = useLoadedSession()
  const [version, setVersion] = useState(0)
  const [notice, setNotice] = useState<Notice>(null)
  const members = useApi(`members|${businessId}|${version}`, (signal) => membersApi.list(businessId!, signal))
  const me = session.user!
  const myRole = context.access.role

  const afterChange = (message: string) => {
    setNotice({ kind: 'success', message })
    setVersion((v) => v + 1)
  }

  return (
    <>
      <PageHeader eyebrow={context.business.name} title="Members" subtitle="Who can see and manage this business." />

      <AddMember
        businessId={businessId!}
        roles={permissions(myRole, me.id, members.data ?? []).addableRoles}
        onAdded={(member) => afterChange(`Added ${member.displayName || member.email} as ${ROLE_LABELS[member.role].toLowerCase()}.`)}
      />

      <Panel title="People" subtitle={ROLE_HELP[myRole === 'DEMO' ? 'VIEWER' : myRole]}>
        <div className="form-stack">
          <div aria-live="polite">
            {notice?.kind === 'success' && <FormSuccess>{notice.message}</FormSuccess>}
            {notice?.kind === 'error' && <FormError>{notice.message}</FormError>}
          </div>
          <AsyncContent
            {...members}
            isEmpty={(list) => list.length === 0}
            emptyMessage="No members yet."
            skeleton={<SkeletonRows rows={4} />}
          >
            {(list) => (
              <MemberTable
                members={list}
                myUserId={me.id}
                myRole={myRole}
                businessId={businessId!}
                timeZone={context.business.timeZone}
                onChanged={(message, changedSelf) => {
                  afterChange(message)
                  // My own role changed: permissions (and this page's controls) follow it.
                  if (changedSelf) refreshContext()
                }}
                onLeft={() => void reload()}
                onError={(message) => setNotice({ kind: 'error', message })}
              />
            )}
          </AsyncContent>
        </div>
      </Panel>
    </>
  )
}

function AddMember({ businessId, roles, onAdded }: { businessId: number; roles: Role[]; onAdded: (member: Member) => void }) {
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Role>('VIEWER')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const touched = useTouched<'email'>()
  const fieldError = emailError(email)

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    touched.touchAll()
    if (fieldError) return
    setBusy(true)
    setError(null)
    try {
      const member = await membersApi.add(businessId, email.trim(), role)
      setEmail('')
      touched.reset()
      onAdded(member ?? { userId: 0, email: email.trim(), displayName: '', role, since: '' })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (roles.length === 0) return null

  return (
    <Panel title="Add a member" subtitle="They need an Insight Studio account first; email invitations are coming later.">
      <form className="form-stack" onSubmit={(e) => void onSubmit(e)} noValidate>
        {error && <FormError>{error}</FormError>}
        <div className="form-inline">
          <TextField
            label="Email"
            type="email"
            name="member-email"
            autoComplete="off"
            inputMode="email"
            spellCheck={false}
            value={email}
            onChange={setEmail}
            onBlur={() => touched.touch('email')}
            error={touched.shows('email') ? fieldError : null}
            disabled={busy}
            fieldClassName="form-grow"
          />
          <SelectField label="Role" value={role} onChange={(v) => setRole(v as Role)} disabled={busy}>
            {roles.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABELS[r]}
              </option>
            ))}
          </SelectField>
          <SubmitButton busy={busy} busyLabel="Adding…" className="form-inline-button">
            Add member
          </SubmitButton>
        </div>
        <p className="form-hint">{ROLE_HELP[role]}</p>
      </form>
    </Panel>
  )
}

interface MemberTableProps {
  members: Member[]
  myUserId: number
  myRole: Role | 'DEMO'
  businessId: number
  timeZone: string
  onChanged: (message: string, changedSelf: boolean) => void
  onLeft: () => void
  onError: (message: string) => void
}

function MemberTable({ members, myUserId, myRole, businessId, timeZone, onChanged, onLeft, onError }: MemberTableProps) {
  const can = permissions(myRole, myUserId, members)
  const [busy, setBusy] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<number | null>(null)

  const run = async (member: Member, action: () => Promise<unknown>, done: () => void) => {
    setBusy(member.userId)
    try {
      await action()
      setConfirming(null)
      done()
    } catch (err) {
      onError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const name = (m: Member) => m.displayName || m.email

  const changeRole = (m: Member, role: Role) =>
    void run(
      m,
      () => membersApi.changeRole(businessId, m.userId, role),
      () => onChanged(`${name(m)} is now ${ROLE_LABELS[role].toLowerCase()}.`, m.userId === myUserId),
    )

  const remove = (m: Member) =>
    void run(
      m,
      () => membersApi.remove(businessId, m.userId),
      () => (m.userId === myUserId ? onLeft() : onChanged(`Removed ${name(m)}.`, false)),
    )

  return (
    <div className="table-scroll">
      <table className="data-table members-table">
        <thead>
          <tr>
            <th scope="col">Member</th>
            <th scope="col">Role</th>
            <th scope="col" className="hide-sm">
              Since
            </th>
            <th scope="col" className="num">
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {members.map((m) => {
            const self = m.userId === myUserId
            const removable = can.canRemove(m) || can.canLeave(m)
            return (
              <tr key={m.userId}>
                <td>
                  <span className="cell-primary break-anywhere">
                    {name(m)}
                    {self && <span className="text-muted"> (you)</span>}
                  </span>
                  <span className="cell-secondary break-anywhere">{m.email}</span>
                </td>
                <td>
                  {can.canChangeRole(m) ? (
                    <select
                      className="control members-role-select"
                      value={m.role}
                      disabled={busy !== null}
                      aria-label={`Role of ${name(m)}`}
                      onChange={(e) => changeRole(m, e.target.value as Role)}
                    >
                      {ROLE_ORDER.map((r) => (
                        <option key={r} value={r}>
                          {ROLE_LABELS[r]}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span
                      className={`role-badge role-${m.role.toLowerCase()}`}
                      title={myRole === 'OWNER' && can.isLastOwner(m) ? 'A business needs at least one owner.' : undefined}
                    >
                      {ROLE_LABELS[m.role]}
                    </span>
                  )}
                </td>
                <td className="hide-sm nowrap">{m.since ? formatSince(m.since, timeZone) : '–'}</td>
                <td className="num">
                  {confirming === m.userId ? (
                    <span className="confirm-inline" role="group" aria-label={self ? 'Leave this business?' : `Remove ${name(m)}?`}>
                      <button type="button" className="button button-danger" disabled={busy !== null} onClick={() => remove(m)}>
                        {busy === m.userId ? (self ? 'Leaving…' : 'Removing…') : self ? 'Leave' : 'Remove'}
                      </button>
                      <button type="button" className="button button-secondary" disabled={busy !== null} onClick={() => setConfirming(null)}>
                        Cancel
                      </button>
                    </span>
                  ) : removable ? (
                    <button
                      type="button"
                      className="button button-secondary"
                      disabled={busy !== null}
                      onClick={() => setConfirming(m.userId)}
                      aria-label={self ? 'Leave this business' : `Remove ${name(m)}`}
                    >
                      {self ? 'Leave' : 'Remove'}
                    </button>
                  ) : null}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function formatSince(instant: string, timeZone: string): string {
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return '–'
  try {
    return new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeZone }).format(date)
  } catch {
    return new Intl.DateTimeFormat('en', { dateStyle: 'medium' }).format(date)
  }
}
