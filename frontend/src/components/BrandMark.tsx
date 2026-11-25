/**
 * Brand mark: a sensor pulse crossing concentric rings.
 *
 * Reads as "signal being picked up", which is what the system does, and stays
 * legible at 28px where a lettermark would not.
 */
export function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
      className="brand-svg"
    >
      <defs>
        <linearGradient id="bm-fill" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop stopColor="var(--accent)" />
          <stop offset="1" stopColor="var(--accent-strong)" />
        </linearGradient>
      </defs>
      <circle cx="16" cy="16" r="16" fill="url(#bm-fill)" />
      {/* One ring: a second reads as noise inside a circular mark. */}
      <circle cx="16" cy="16" r="10" stroke="#fff" strokeOpacity="0.3" strokeWidth="1.1" />
      {/* The pulse itself. */}
      <path
        d="M6.5 16.5h3.2l2-4.4 2.9 8.6 2.2-5.6 1.5 2.8h6.2"
        stroke="#fff"
        strokeWidth="1.9"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
