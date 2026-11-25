/**
 * Disclosure chevron. A drawn stroke rather than the ▾ glyph, which renders
 * inconsistently across platforms and sits off the optical centre. Rotating a
 * single icon also lets the open and closed states transition into each other
 * instead of swapping characters.
 */
export function Chevron({ open, size = 14 }: { open: boolean; size?: number }) {
  return (
    <svg
      className={`chevron ${open ? 'open' : ''}`}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M4.5 6.25 8 9.75l3.5-3.5"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
