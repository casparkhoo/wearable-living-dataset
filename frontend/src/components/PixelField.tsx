/**
 * A field of tiny square pixels that fills the whole page, very faint, in dark greens with
 * a few lime ones. It hardly moves: a small share of the pixels pick a new brightness on
 * each step and ease toward it, so the field shimmers slowly. Stepped at four frames a
 * second it reads as 8-bit. Under prefers-reduced-motion it is drawn once and stays still.
 */
import { useEffect, useRef } from 'react';

const CELL = 12;
const FRAME_MS = 150;
const CHANGE_SHARE = 0.12;
const EASE = 0.35;

export function PixelField() {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    let cols = 0;
    let rows = 0;
    let level = new Float32Array(0);
    let target = new Float32Array(0);
    let lime = new Uint8Array(0);
    let timer = 0;

    const night = () => document.documentElement.getAttribute('data-theme') === 'dark';

    const paintCell = (i: number, dark: boolean) => {
      const c = i % cols;
      const r = Math.floor(i / cols);
      const v = level[i];
      let style: string;
      if (lime[i]) {
        style = `rgba(95, 208, 34, ${(0.008 + v * (dark ? 0.085 : 0.06)).toFixed(3)})`;
      } else {
        style = dark
          ? `rgba(95, 208, 34, ${(0.008 + v * 0.04).toFixed(3)})`
          : `rgba(60, 140, 20, ${(0.01 + v * 0.03).toFixed(3)})`;
      }
      ctx.clearRect(c * CELL, r * CELL, CELL, CELL);
      ctx.fillStyle = style;
      ctx.fillRect(c * CELL + 0.5, r * CELL + 0.5, CELL - 1.5, CELL - 1.5);
    };

    const drawAll = () => {
      ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
      const dark = night();
      for (let i = 0; i < level.length; i++) paintCell(i, dark);
    };

    const resize = () => {
      const ratio = window.devicePixelRatio || 1;
      canvas.width = Math.floor(window.innerWidth * ratio);
      canvas.height = Math.floor(window.innerHeight * ratio);
      canvas.style.width = `${window.innerWidth}px`;
      canvas.style.height = `${window.innerHeight}px`;
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      cols = Math.ceil(window.innerWidth / CELL);
      rows = Math.ceil(window.innerHeight / CELL);
      const count = cols * rows;
      level = new Float32Array(count);
      target = new Float32Array(count);
      lime = new Uint8Array(count);
      for (let i = 0; i < count; i++) {
        level[i] = target[i] = Math.random();
        lime[i] = Math.random() < 1 / 14 ? 1 : 0;
      }
      drawAll();
    };

    const tick = () => {
      const dark = night();
      const changes = Math.max(1, Math.floor(level.length * CHANGE_SHARE));
      for (let k = 0; k < changes; k++) target[Math.floor(Math.random() * level.length)] = Math.random();
      // Only the pixels that are still easing are redrawn.
      for (let i = 0; i < level.length; i++) {
        const d = target[i] - level[i];
        if (d > 0.01 || d < -0.01) {
          level[i] += d * EASE;
          paintCell(i, dark);
        }
      }
    };

    resize();
    window.addEventListener('resize', resize);
    if (!reduceMotion) timer = window.setInterval(tick, FRAME_MS);
    return () => {
      window.removeEventListener('resize', resize);
      window.clearInterval(timer);
    };
  }, []);

  return <canvas ref={ref} className="pixel-field" aria-hidden="true" />;
}
