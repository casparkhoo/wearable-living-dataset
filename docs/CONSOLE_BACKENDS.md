# Choosing the backend in the web console

One console build can talk to several backends. The choice is made in the browser at run time and is
remembered per browser (localStorage); nothing is stored on a server.

## What you see

- The header always shows **Backend  <name>  <host>** (also on the sign-in screen). Click it to switch with
  one click, add a backend, or remove one. Settings lists the same backends.
- **Cloud** is the build-time default, `VITE_BACKEND_URL`. A console served by its own gateway
  (`VITE_BACKEND_URL=same-origin`) calls this default **This server**. A dev server with nothing configured
  offers **Local** (`http://127.0.0.1:8080`). The default cannot be removed.
- Backends you add are named by you (default: the host).

## Adding a backend

Type an address, or open a link:

    https://<console>/?backend=<https url>[&name=<label>][&broker=<mqtts url>]

The console shows the host and asks for confirmation before adding and selecting it. The parameters are then
removed from the address bar. (`/setup?backend=...` is the phone-app setup link and is not affected.)

Accepted addresses: `https://` anywhere; `http://` only for `localhost` / `127.0.0.1`. Addresses with a user
name or password, other schemes, and anything unparsable are rejected. `broker` must be `mqtts://`.

## What switching does

The console is rebuilt for the new backend: polling of the old one stops, held live readings, history,
exports, studies and the cached identity are discarded, so nothing from one backend is shown under another's
name. The "Get the app" setup link and QR use the active backend (and its broker if one is known: the build-time
`VITE_BROKER_URL` for the default, or the `broker` from the link for others).

## Sign-in and credentials

Sign-in is a Supabase session that belongs to the **console build** (`VITE_SUPABASE_URL`), not to a backend.
After a switch the same session is sent to the new backend, which must trust the same Supabase project.

- If it does, you stay signed in and the backend maps you to its own account and roles.
- If it uses a different project (or different auth settings), it answers 401 and the sign-in screen appears.
  This cannot be fixed from the console; use a console built for that backend (for example the one its
  gateway serves). The UI says so.
- The access token, and the legacy shared bearer token, are attached only to requests for the active
  backend's origin; the legacy shared token only for the built-in default. Adding a backend sends your token to
  that host, which is why a link needs confirmation. Only add servers you trust.

## Mixed content

An https console cannot call a plain-http backend; the browser blocks it. Only `http://localhost` and
`http://127.0.0.1` are accepted, and Chrome, Edge and Firefox allow those from https pages. Safari does not.
The console shows a message when the active backend is plain http and the page is https.

## Local console versus the Vercel one

| | Vercel console | Local console (`deployment/local`) |
|---|---|---|
| Served from | https, public | the gateway, same origin as the API |
| Default backend | `VITE_BACKEND_URL` (Cloud) | `same-origin` (This server) |
| Switching to another backend | needs https, or localhost in Chrome/Edge/Firefox | same rules; the backend must allow this origin (CORS) |
| Sign-in | its Supabase project | the Supabase project the local build was made with |

Browsers also require the target backend to allow the console's origin in its CORS settings.
