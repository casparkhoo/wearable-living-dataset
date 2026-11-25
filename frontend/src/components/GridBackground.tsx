/**
 * Decorative backdrop: a drifting dot grid with a soft colour glow that
 * follows the pointer.
 *
 * Kept deliberately quiet. The dots are low contrast, the glow is a wide
 * low-opacity radial, and both are masked to fade at the edges so nothing
 * competes with the content in front of them.
 *
 * Pointer tracking is coalesced into one animation frame, so moving the mouse
 * fast cannot queue up style writes. Under prefers-reduced-motion the drift
 * and the glow both stop; a static grid remains.
 */
import { useEffect, useRef } from 'react';

export function GridBackground() {
  const glowRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduceMotion) return;

    const glow = glowRef.current;
    if (!glow) return;

    let frame = 0;
    let pending: { x: number; y: number } | null = null;

    const apply = () => {
      frame = 0;
      if (!pending) return;
      glow.style.setProperty('--gx', `${pending.x}px`);
      glow.style.setProperty('--gy', `${pending.y}px`);
      glow.style.opacity = '1';
      pending = null;
    };

    const onMove = (event: PointerEvent) => {
      pending = { x: event.clientX, y: event.clientY };
      if (!frame) frame = window.requestAnimationFrame(apply);
    };
    const onLeave = () => {
      glow.style.opacity = '0';
    };

    window.addEventListener('pointermove', onMove, { passive: true });
    document.addEventListener('pointerleave', onLeave);
    return () => {
      window.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerleave', onLeave);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, []);

  return (
    <div className="grid-bg" aria-hidden="true">
      <div className="grid-bg-dots" />
      <div className="grid-bg-glow" ref={glowRef} />
    </div>
  );
}
