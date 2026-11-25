/** "Get the Android app" card: download, checksum, QR with the setup link, install steps. */
import { useMemo } from 'react';
import qrcode from 'qrcode-generator';

import { buildSchemeLink, buildSetupLink, formatBytes, readApkInfo } from '../lib/appSetup';

function QrSvg({ text }: { text: string }) {
  const svg = useMemo(() => {
    const qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    return qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
  }, [text]);
  return <div className="app-qr" role="img" aria-label="QR code with the setup link" dangerouslySetInnerHTML={{ __html: svg }} />;
}

export function AppDownload({ backendUrl, brokerUrl, compact = false }: { backendUrl: string; brokerUrl?: string; compact?: boolean }) {
  const apk = readApkInfo(import.meta.env as Record<string, string | undefined>);
  const broker = brokerUrl?.trim() || undefined;
  const setupLink = buildSetupLink(window.location.origin, backendUrl, broker);
  const schemeLink = buildSchemeLink(backendUrl, broker);

  return (
    <div className="app-download">
      <div className={compact ? undefined : 'settings-head'}>
        <div>
          <div className="settings-title">Get the Android app</div>
          <div className="settings-sub">
            The phone app streams your wearable to this console. Scan the code on your phone to
            open the setup link, or download the app directly.
          </div>
        </div>
      </div>
      <div className="app-download-body">
        <div className="app-download-main">
          {apk ? (
            <>
              <a className="conn-btn btn-primary app-btn" href={apk.url} download>Download the app (.apk)</a>
              <dl className="app-facts">
                {apk.version && (<><dt>Version</dt><dd>{apk.version}</dd></>)}
                {apk.sizeBytes && (<><dt>Size</dt><dd>{formatBytes(apk.sizeBytes)}</dd></>)}
                {apk.sha256 && (<><dt>SHA-256</dt><dd><code>{apk.sha256}</code></dd></>)}
              </dl>
            </>
          ) : (
            <div className="signin-note">
              The app has not been published yet. Ask the study team for the file, or build it
              from the source code (see <code>docs/APK_DISTRIBUTION.md</code> in the repository).
            </div>
          )}
          {!compact && (
            <ol className="app-steps">
              <li>Download the file on your phone and open it. If asked, allow installs from this source.</li>
              <li>Open the app. If you scanned the code, it already knows this server.</li>
              <li>Otherwise pick this server in the app, then sign in.</li>
            </ol>
          )}
        </div>
        {setupLink ? (
          <div className="app-download-qr">
            <QrSvg text={setupLink} />
            <div className="app-qr-caption">Scan to set up the server</div>
            {schemeLink && <a className="link-btn" href={schemeLink}>Open in the app</a>}
          </div>
        ) : (
          <div className="signin-note">
            No secure backend address is configured, so there is no setup code yet.
          </div>
        )}
      </div>
    </div>
  );
}
