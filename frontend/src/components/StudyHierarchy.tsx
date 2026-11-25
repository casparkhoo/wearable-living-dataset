/**
 * Shows how a study nests down to evidence.
 *
 * Drawn as a left-to-right flow rather than an indented list, because the
 * relationship people get wrong is containment — that evidence belongs to a
 * device recording, which belongs to an experiment, which belongs to a
 * participant *within one study* — and a chain reads that more directly than
 * nesting does.
 *
 * Roles are not repeated here; they are shown where they apply, on each study
 * row and in the account menu.
 */
const STEPS = [
  {
    key: 'study',
    label: 'Study',
    detail: 'One research project',
    sub: 'Consent, status and membership live here',
  },
  {
    key: 'participants',
    label: 'Participants',
    detail: 'People who joined it',
    sub: 'Invited, then consent recorded before capture',
  },
  {
    key: 'experiments',
    label: 'Experiments',
    detail: 'One recording session',
    sub: 'Started from the phone by that participant',
  },
  {
    key: 'recordings',
    label: 'Device recordings',
    detail: 'One per wearable',
    sub: 'Each keeps its own time base',
  },
  {
    key: 'evidence',
    label: 'Evidence',
    detail: 'Packets and samples',
    sub: 'Raw payload plus canonical channels',
  },
];

export function StudyHierarchy() {
  return (
    <div className="hierarchy">
      <div className="hierarchy-heading">How a study is organised</div>

      <ol className="flow">
        {STEPS.map((step, index) => (
          <li className="flow-step" key={step.key}>
            <div className={`flow-card flow-${step.key}`}>
              <span className="flow-index">{index + 1}</span>
              <span className="flow-label">{step.label}</span>
              <span className="flow-detail">{step.detail}</span>
              <span className="flow-sub">{step.sub}</span>
            </div>
            {index < STEPS.length - 1 && (
              <span className="flow-arrow" aria-hidden="true">
                <svg viewBox="0 0 24 12" fill="none">
                  <path
                    d="M1 6h20m0 0-5-4.5M21 6l-5 4.5"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </span>
            )}
          </li>
        ))}
      </ol>

      <p className="hierarchy-note">
        Every experiment and device recording carries the study it belongs to, so studies
        share one database while staying scoped apart — one study&rsquo;s evidence is never
        visible from another. Withdrawing from a study stops further capture but keeps the
        evidence already collected.
      </p>
    </div>
  );
}
