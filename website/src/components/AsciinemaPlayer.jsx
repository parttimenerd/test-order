import React, { useEffect, useRef, useState } from 'react';
import BrowserOnly from '@docusaurus/BrowserOnly';
import useBaseUrl from '@docusaurus/useBaseUrl';

const GITHUB_PAGES_BASE = 'https://parttimenerd.github.io/test-order';

function PlayerImpl({ src, autoPlay = false, loop = false, cols, rows, poster }) {
  const ref = useRef(null);
  const resolvedSrc = useBaseUrl(src);

  useEffect(() => {
    let cancelled = false;
    let player;
    (async () => {
      // Try the local URL first; fall back to GitHub Pages for casts not yet
      // recorded locally (e.g. during local dev before running record-all-demos.sh).
      let effectiveSrc = resolvedSrc;
      try {
        const res = await fetch(resolvedSrc, { method: 'HEAD' });
        if (!res.ok) {
          const remote = GITHUB_PAGES_BASE + src;
          const remoteRes = await fetch(remote, { method: 'HEAD' });
          if (remoteRes.ok) effectiveSrc = remote;
          else return; // neither local nor remote — render nothing
        }
      } catch {
        return;
      }

      const mod = await import('asciinema-player');
      await import('asciinema-player/dist/bundle/asciinema-player.css');
      if (cancelled || !ref.current) return;
      player = mod.create(effectiveSrc, ref.current, {
        autoPlay,
        loop,
        cols,
        rows,
        poster,
        idleTimeLimit: 1.5,
        theme: 'asciinema',
        fit: 'width',
      });
    })();
    return () => {
      cancelled = true;
      if (player && typeof player.dispose === 'function') player.dispose();
    };
  }, [resolvedSrc, autoPlay, loop, cols, rows, poster]);

  return <div ref={ref} />;
}

/**
 * Wraps the asciinema-player npm package. Lazy-loads on the client side only.
 * If the local cast 404s, transparently falls back to the GitHub Pages copy.
 */
export default function AsciinemaPlayer(props) {
  return (
    <BrowserOnly fallback={<div style={{ minHeight: 300 }} />}>
      {() => <PlayerImpl {...props} />}
    </BrowserOnly>
  );
}
