/** Names as people say them: the τ-Ring is what the firmware calls "OpenRing". */
export function friendly(text: string): string {
  return text.replace(/OpenRing/g, 'τ-Ring');
}
