import { useLayoutEffect, type RefObject } from 'react';

/**
 * Keep an absolutely positioned popover inside the window.
 *
 * The popover is anchored to its trigger (right-aligned, just below it), which
 * is right on a wide window and wrong on a phone, where the trigger sits near
 * the left edge and the popover runs off the screen. After it opens, this
 * measures it and nudges it sideways until it is `margin` px inside the
 * viewport, and caps its height so a long popover scrolls instead of running
 * off the bottom. It re-measures when the window is resized.
 */
export function useClampToViewport(ref: RefObject<HTMLElement>, open: boolean, margin = 12): void {
  useLayoutEffect(() => {
    const el = ref.current;
    if (!open || !el) return;

    const clamp = () => {
      el.style.right = '0px';
      el.style.maxHeight = '';
      const viewportWidth = document.documentElement.clientWidth;
      const viewportHeight = window.innerHeight;
      const rect = el.getBoundingClientRect();
      let shift = 0; // positive moves the popover left, negative right
      if (rect.right > viewportWidth - margin) shift = rect.right - (viewportWidth - margin);
      if (rect.left - shift < margin) shift = rect.left - margin;
      if (shift !== 0) el.style.right = `${shift}px`;
      const room = viewportHeight - rect.top - margin;
      el.style.maxHeight = `${Math.max(160, room)}px`;
    };

    clamp();
    window.addEventListener('resize', clamp);
    return () => {
      window.removeEventListener('resize', clamp);
      el.style.right = '';
      el.style.maxHeight = '';
    };
  }, [ref, open, margin]);
}
