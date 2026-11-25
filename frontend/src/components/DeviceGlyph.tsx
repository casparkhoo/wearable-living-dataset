/** A clean line icon for each kind of wearable, drawn white on the device's colour. */
export function DeviceGlyph({ name }: { name: string }) {
  const common = {
    width: 20,
    height: 20,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  };
  if (name.includes('Ring')) {
    return (
      <svg {...common}>
        <ellipse cx="12" cy="14.5" rx="7" ry="6" />
        <ellipse cx="12" cy="14.5" rx="4.2" ry="3.4" />
        <path d="M9.2 6.2 L12 3.4 L14.8 6.2 L12 9 Z" />
      </svg>
    );
  }
  if (name.includes('Glasses')) {
    return (
      <svg {...common}>
        <rect x="2.2" y="8.6" width="8.4" height="6.6" rx="2.6" />
        <rect x="13.4" y="8.6" width="8.4" height="6.6" rx="2.6" />
        <path d="M10.6 11.4 Q12 10.2 13.4 11.4" />
        <path d="M2.2 10.4 L0.9 8.2 M21.8 10.4 L23.1 8.2" />
      </svg>
    );
  }
  if (name.includes('HeartRate')) {
    return (
      <svg {...common}>
        <path d="M12 20 C5 15 3 11.5 3 8.8 C3 6.4 4.8 4.8 7 4.8 C9 4.8 11 6 12 8 C13 6 15 4.8 17 4.8 C19.2 4.8 21 6.4 21 8.8 C21 11.5 19 15 12 20 Z" />
      </svg>
    );
  }
  if (name.includes('Earable')) {
    return (
      <svg {...common}>
        <circle cx="10.5" cy="9" r="4.6" />
        <path d="M15 11.5 L15 19.5 Q15 21 13.4 21 Q12 21 12 19.5 L12 13.6" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <circle cx="12" cy="12" r="3" />
      <circle cx="12" cy="12" r="8" />
    </svg>
  );
}
