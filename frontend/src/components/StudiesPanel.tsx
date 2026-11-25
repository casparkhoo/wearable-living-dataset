/**
 * Study management.
 *
 * What this panel offers depends on the caller's role, which the backend
 * decides — every action here can be refused server-side, and the UI only
 * avoids offering what would obviously fail. A platform admin sees every study;
 * a study admin sees and administers their own; a participant sees the studies
 * they belong to and can record consent or withdraw.
 */
import { useCallback, useEffect, useState } from 'react';

import { backendFetch } from '../hooks/useBackend';
import type { BackendIdentity } from '../hooks/useAuth';
import { StudyHierarchy } from './StudyHierarchy';

interface StudySummary {
  id: number;
  name: string;
  status: string;
  consent_version: string | null;
  ethics_reference: string | null;
  my_role: string | null;
  member_count: number;
}

interface StudyDetail {
  id: number;
  name: string;
  description: string | null;
  status: string;
  consent_version: string | null;
  ethics_reference: string | null;
  created_by_user_id: number;
  active_members: number;
  experiments: number;
}

interface StudyMember {
  user_id: number;
  participant_code: string | null;
  display_name: string | null;
  role: string;
  status: string;
  consent_version: string | null;
  consent_recorded: boolean;
  joined_at: string | null;
  withdrawn_at: string | null;
}

const STATUSES = ['draft', 'recruiting', 'active', 'closed', 'archived'];

interface Props {
  backendUrl: string;
  identity: BackendIdentity | null;
  onIdentityChanged: () => void;
}

export function StudiesPanel({ backendUrl, identity, onIdentityChanged }: Props) {
  const base = backendUrl.replace(/\/+$/, '');
  const signedIn = Boolean(identity?.authenticated);

  const [studies, setStudies] = useState<StudySummary[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<StudyDetail | null>(null);
  const [members, setMembers] = useState<StudyMember[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [newName, setNewName] = useState('');
  const [inviteValue, setInviteValue] = useState('');
  const [inviteRole, setInviteRole] = useState('participant');

  /** Pull the error message the backend sent, rather than inventing one. */
  const readError = async (response: Response, fallback: string): Promise<string> => {
    try {
      const payload = await response.json();
      return (payload?.error as string) || (payload?.detail as string) || fallback;
    } catch {
      return fallback;
    }
  };

  const loadStudies = useCallback(async () => {
    if (!base || !signedIn) {
      setStudies([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const response = await backendFetch(`${base}/api/studies`);
      if (!response.ok) {
        setError(await readError(response, `Studies returned ${response.status}`));
        setStudies([]);
        return;
      }
      const payload = await response.json();
      setStudies((payload.studies ?? []) as StudySummary[]);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not load studies.');
    } finally {
      setLoading(false);
    }
  }, [base, signedIn]);

  const loadDetail = useCallback(
    async (studyId: number) => {
      setError(null);
      setMembers(null);
      try {
        const response = await backendFetch(`${base}/api/studies/${studyId}`);
        if (!response.ok) {
          setError(await readError(response, `Study returned ${response.status}`));
          setDetail(null);
          return;
        }
        setDetail((await response.json()) as StudyDetail);

        // Membership is admin-only; a participant simply does not get this list.
        const membersResponse = await backendFetch(`${base}/api/studies/${studyId}/members`);
        if (membersResponse.ok) {
          const payload = await membersResponse.json();
          setMembers((payload.members ?? []) as StudyMember[]);
        }
      } catch (caught) {
        setError(caught instanceof Error ? caught.message : 'Could not load the study.');
      }
    },
    [base],
  );

  useEffect(() => {
    void loadStudies();
  }, [loadStudies]);

  useEffect(() => {
    if (selectedId !== null) void loadDetail(selectedId);
    else setDetail(null);
  }, [selectedId, loadDetail]);

  const act = async (
    path: string,
    body: unknown,
    successMessage: string,
    method = 'POST',
  ): Promise<boolean> => {
    setError(null);
    setNotice(null);
    try {
      const response = await backendFetch(`${base}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      if (!response.ok) {
        setError(await readError(response, `Request failed (${response.status})`));
        return false;
      }
      setNotice(successMessage);
      await loadStudies();
      if (selectedId !== null) await loadDetail(selectedId);
      onIdentityChanged();
      return true;
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Request failed.');
      return false;
    }
  };

  /**
   * Erase a participant's data in this study, on their request.
   *
   * A dry run first shows what would be deleted; the admin then types ERASE.
   * The backend refuses anything else, logs the erasure, and marks the
   * membership withdrawn.
   */
  const eraseMember = async (studyId: number, member: StudyMember) => {
    setError(null);
    setNotice(null);
    const path = `/api/studies/${studyId}/members/${member.user_id}/erase`;
    try {
      const preview = await backendFetch(`${base}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dry_run: true }),
      });
      if (!preview.ok) {
        setError(await readError(preview, `Erasure preview failed (${preview.status})`));
        return;
      }
      const counts = (await preview.json()).would_delete ?? {};
      const summary = Object.entries(counts)
        .filter(([, n]) => Number(n) > 0)
        .map(([table, n]) => `${n} ${table}`)
        .join(', ');
      const who = member.display_name ?? member.participant_code ?? `#${member.user_id}`;
      const typed = window.prompt(
        `Permanently erase ${who}'s data in this study?

This deletes: ${summary || 'nothing recorded yet'}.
It cannot be undone, and is logged. Backups age out on their own rotation.

Type ERASE to confirm.`,
        '',
      );
      if (typed === null) return;
      const reason = window.prompt('Reason, for the erasure log (e.g. "participant request, 9 Nov"):', 'participant request');
      if (reason === null) return;
      await act(path, { confirm: typed.trim(), reason }, `Erased ${who}'s data in this study. The erasure is logged.`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Erasure failed.');
    }
  };

  // The hierarchy is explanatory, so it is shown even without an account: it
  // is most useful to someone who does not yet know how any of this nests.
  if (!signedIn) {
    return (
      <div className="studies-panel">
        <StudyHierarchy />
        <div className="stream-empty">
          Creating or joining a study needs a signed-in account. The deployment-wide token
          has no identity, so the backend cannot tell who would be joining.
        </div>
      </div>
    );
  }

  // Accounts start as participants; a platform admin grants "researcher" to
  // whoever runs studies. The backend enforces this too.
  const canCreate = ['researcher', 'platform_admin'].includes(identity?.platform_role ?? '');

  const myMembership = members?.find((member) => member.user_id === identity?.user_id);
  const canAdminister =
    identity?.platform_role === 'platform_admin' ||
    ['study_admin', 'researcher'].includes(myMembership?.role ?? '') ||
    ['study_admin', 'researcher'].includes(
      studies.find((study) => study.id === selectedId)?.my_role ?? '',
    );

  return (
    <div className="studies-panel">
      <StudyHierarchy />
      {error && <div className="settings-error">{error}</div>}
      {notice && <div className="settings-detail">{notice}</div>}

      {canCreate ? (
      <div className="settings-field">
        <span>Create a study</span>
        <input
          className="conn-input settings-input"
          value={newName}
          onChange={(event) => setNewName(event.target.value)}
          placeholder="Study name"
        />
        <button
          className="conn-btn btn-primary"
          disabled={!newName.trim()}
          onClick={async () => {
            const created = await act(
              '/api/studies',
              // Consent version and ethics reference are still stored, but are
              // not asked for until the governance wording is settled.
              { name: newName.trim() },
              'Study created. You are its study admin.',
            );
            if (created) setNewName('');
          }}
        >
          Create study
        </button>
      </div>
      ) : (
        <div className="settings-detail">
          <span>Your account</span>
          <span>
            {identity?.email ?? `#${identity?.user_id}`}
            {identity?.participant_code ? ` · code ${identity.participant_code}` : ''}
          </span>
        </div>
      )}

      <div className="settings-head">
        <div className="panel-title">
          Studies {loading ? '' : `(${studies.length})`}
        </div>
        <button className="conn-btn" onClick={() => void loadStudies()} disabled={loading}>
          {loading ? 'Loading' : 'Refresh'}
        </button>
      </div>

      {!loading && studies.length === 0 && (
        <div className="stream-empty">
          {canCreate
            ? 'You are not a member of any study yet. Create one above, or ask a study admin to invite you.'
            : 'You are not in a study yet. Give your email above to the researcher running the study; once they invite you, it appears here for you to consent.'}
        </div>
      )}

      <div className="history-list">
        {studies.map((study) => (
          <article className="history-card" key={study.id}>
            <button
              className="history-summary"
              onClick={() => setSelectedId(selectedId === study.id ? null : study.id)}
            >
              <span className="history-chevron">{selectedId === study.id ? '▾' : '▸'}</span>
              <span className="history-name">
                #{study.id} · {study.name}
              </span>
              <span>{study.status}</span>
              <span>{study.my_role ?? 'platform admin'}</span>
              <span>
                {study.member_count} member{study.member_count === 1 ? '' : 's'}
              </span>
            </button>

            {selectedId === study.id && (
              <div className="history-detail">
                {!detail ? (
                  <div className="stream-empty">Loading study…</div>
                ) : (
                  <>
                    <div className="settings-detail">
                      <span>Experiments</span>
                      <span>{detail.experiments}</span>
                    </div>

                    {canAdminister && (
                      <>
                        <div className="settings-field">
                          <span>Status</span>
                          <select
                            className="conn-input settings-input"
                            value={detail.status}
                            onChange={(event) =>
                              void act(
                                `/api/studies/${study.id}`,
                                { status: event.target.value },
                                `Status set to ${event.target.value}.`,
                                'PATCH',
                              )
                            }
                          >
                            {STATUSES.map((status) => (
                              <option key={status} value={status}>
                                {status}
                              </option>
                            ))}
                          </select>
                        </div>

                        <div className="settings-field">
                          <span>Invite a member</span>
                          <input
                            className="conn-input settings-input"
                            value={inviteValue}
                            onChange={(event) => setInviteValue(event.target.value)}
                            placeholder="Email or participant code"
                          />
                          <select
                            className="conn-input settings-input"
                            value={inviteRole}
                            onChange={(event) => setInviteRole(event.target.value)}
                          >
                            <option value="participant">participant</option>
                            <option value="researcher">researcher</option>
                            <option value="study_admin">study_admin</option>
                          </select>
                          <button
                            className="conn-btn btn-primary"
                            disabled={!inviteValue.trim()}
                            onClick={async () => {
                              const value = inviteValue.trim();
                              // The backend wants exactly one identifier; choose
                              // by whether this looks like an email address.
                              const body = value.includes('@')
                                ? { email: value, role: inviteRole }
                                : { participant_code: value, role: inviteRole };
                              const ok = await act(
                                `/api/studies/${study.id}/members`,
                                body,
                                'Invitation recorded. They must consent before recording.',
                              );
                              if (ok) setInviteValue('');
                            }}
                          >
                            Invite
                          </button>
                        </div>

                        {members && (
                          <div className="studies-members">
                            <div className="panel-title">Members</div>
                            {members.map((member) => (
                              <div className="settings-detail" key={member.user_id}>
                                <span>
                                  #{member.user_id}{' '}
                                  {member.display_name ?? member.participant_code ?? ''} ·{' '}
                                  {member.role}
                                </span>
                                <span>
                                  {member.status}
                                  {member.consent_recorded ? ' · consented' : ' · no consent'}
                                  {member.role === 'participant' && (
                                    <button
                                      className="conn-btn btn-erase"
                                      onClick={() => void eraseMember(study.id, member)}
                                    >
                                      Erase data
                                    </button>
                                  )}
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                      </>
                    )}

                    {/* Consent is always recorded by the participant themselves. */}
                    {myMembership?.status !== 'withdrawn' && (
                      <div className="btn-row studies-consent">
                        {!myMembership?.consent_recorded && (
                          <button
                            className="conn-btn btn-primary"
                            onClick={() =>
                              void act(
                                `/api/studies/${study.id}/consent`,
                                {},
                                'Consent recorded. You can now record into this study.',
                              )
                            }
                          >
                            Record my consent
                          </button>
                        )}
                        <button
                          className="conn-btn"
                          onClick={() => {
                            const reason = window.prompt(
                              'Withdraw from this study? Already-collected evidence is retained; this stops further recording.',
                              '',
                            );
                            if (reason === null) return;
                            void act(
                              `/api/studies/${study.id}/withdraw`,
                              { reason },
                              'Withdrawn. Evidence already collected is retained.',
                            );
                          }}
                        >
                          Withdraw
                        </button>
                      </div>
                    )}
                  </>
                )}
              </div>
            )}
          </article>
        ))}
      </div>
    </div>
  );
}
