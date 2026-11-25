# Troubleshooting

Failures that have actually happened while building and running this system, with how to
recognise each and what to do. For the local stack's port, sign-in and phone-offline problems
see also the table at the end of [LOCAL_HOSTING.md](LOCAL_HOSTING.md).

Always start with the logs and the health endpoint:

```bash
scripts/local.sh status               # containers, health, addresses
scripts/local.sh logs backend         # also: mysql, mosquitto, gateway, papagei, broker-accounts
curl http://localhost:8088/health     # any answer (even 401) means the backend is reachable
```

## The phone cannot reach the server, and its clock is wrong

**Symptom.** The app cannot sign in or connect over HTTPS or WSS; TLS errors, "certificate not yet
valid", token errors; labels look offset in time.

**Cause.** On the test phone the date was set about three months in the past. A certificate issued
after the phone's date looks not yet valid, token expiry checks go wrong, and the phone's
timestamps (which labels are matched on) shift.

**Check and fix.** `adb shell date` and compare with the computer. Turn on automatic date and time
on the phone. Do this before debugging TLS or authentication.

## Tailscale is not running on the host

**Symptom.** The phone shows "offline" on mobile data or another network although the stack is
healthy on the computer; the `https://<machine>.<tailnet>.ts.net` address does not answer
(`http://localhost:8088` on the computer itself still does).

**Cause.** That address comes from `tailscale serve`, which needs the Tailscale service running on
the computer (and on the phone). If the service has stopped (after a reboot, an update, or being
quit) the address stops answering.

**Fix.** Start the Tailscale service or app on the computer, then `tailscale status` and
`tailscale serve status`; re-run `tailscale serve --bg 8088` if nothing is served. The computer
must also stay awake. See [LOCAL_HOSTING.md](LOCAL_HOSTING.md), step 4. For a public address
(`tailscale funnel`) the same applies, and turn it off when not needed.

## Broker says "not authorised" after switching servers

**Symptom.** The app's MQTT log repeats "not authorised" (or "could not connect as u2"), but sign-in
works.

**Cause.** Broker accounts are named by user id (`u<id>`), and two different servers (your laptop and
the cloud VM, say) can both have a user `u2` with different passwords. The password is derived
from each server's own secret. A phone moved between them kept retrying the old server's
password. The app now switches the broker login when the password changes, not only the name, so
update to a build with that fix. A phone with a stale manually entered account in the MQTT tab
can still hit it.

**Check and fix.** Sign out and in again, or clear the account in the MQTT tab. Confirm the broker
accounts exist on the server you are using: `scripts/local.sh logs broker-accounts`
(on a cloud server, `scripts/sync_mqtt_users.sh`). New accounts appear within a minute of a
user's first sign-in.

## The app was built for a different instance

**Symptom.** The app signs in but cannot reach your server, or it quietly talks to another
deployment (it once fell back to the cloud VM instead of the local stack).

**Cause.** The backend URL, broker URL and Supabase project are compiled into the app from
`android-application/android/instance.properties` (or `-P` options) when it is built. An APK only
works for the instance it was built for.

**Fix.** Rebuild for the instance you want, `scripts/local.sh phone <url>` for the local stack, or
edit `instance.properties` and run `./gradlew :app:assembleDebug`. The app's MQTT tab shows the broker address it
uses. Never commit `instance.properties`.

## The ring's sample index wraps or restarts

**Symptom.** Heart rate or loss figures look wrong on a long ring recording; samples seem to be
out of order or duplicated when sorted by `sample_index`; a continuity report shows "resets".

**Cause.** The ring's `sample_index` is a counter that wraps within a long recording. It cannot be used on its own to order samples or to count missing ones.

**What the code does.** The live engine treats an index jump larger than 1,000 as a counter restart
(`INDEX_RESET_JUMP` in `backend/src/live.rs`), not as lost samples. `scripts/train_head_from_rules.py`
orders ring samples by the order they were stored (`stream_samples.id`) and drops an exact repeat
of a sample, because a redelivered batch repeats rows. If you write new analysis over ring data,
order by arrival, not by index, and treat a large jump as a restart.

## Firefox on the host drops requests to its own Tailscale address

**Symptom.** A hosted console (for example on Vercel) pointed at your local instance works from
other devices but not in Firefox on the computer that runs the instance; requests fail with no
useful error.

**Cause.** A browser on the computer reaches the instance at Tailscale's private address rather
than through Funnel. A page served from the public internet requesting a private address is a
"private network access" request. The backend allows it (`allow_private_network` in
`backend/src/main.rs`, with request headers mirrored rather than wildcarded), but Firefox on the host
computer was seen dropping the requests anyway. It was not tracked down further, so treat this as
a browser-and-host behaviour rather than a backend error.

**Workaround.** On the host, open the instance's own address instead (`http://localhost:8088`;
it serves the same console), or test the hosted console from a device without Tailscale or in
another browser. See [LOCAL_HOSTING.md](LOCAL_HOSTING.md), "A hosted console pointed at this computer".

## Windows blocks scikit-learn estimators

**Symptom.** On the Windows development machine, fitting a scikit-learn estimator fails because
Windows Application Control refused to load a compiled module it needs.

**Cause.** The machine's application-control policy, not this repository. It affects estimators
that load compiled extensions that the policy does not trust.

**Workaround.** Avoid scikit-learn where you can. `scripts/train_head_from_rules.py` fits its ridge
regression with plain numpy and so does not depend on scikit-learn, whereas
`scripts/papagei_posthoc.py` uses scikit-learn's `RidgeCV`. If you need an estimator that is
blocked, run the script on another machine or in a container.

## Other things that have bitten

| Symptom | Cause and fix |
|---|---|
| `ports are not available` on `scripts/local.sh up` | Something else uses 8088, 11883 or 18080. Set `GATEWAY_PORT`, `MQTT_HOST_PORT` or `BACKEND_HOST_PORT` in `deployment/local/.env`. |
| Console says the backend did not recognise the session | `SUPABASE_URL` in `.env` is not the project you signed in to. See `scripts/local.sh logs backend`. |
| A new head does not show up | The worker image copies `ml-inference-server/heads/` at build time. Rebuild (`scripts/local.sh up`) and check `ML_LIVE_HEAD_ID`. |
| Heart rate on screen disappears for a few seconds | A stalled stream. The console and app keep the last reading, frozen, with a Delayed or Offline status; if it stays blank, check the wearable's connection and `scripts/local.sh logs backend`. |
| Recording refuses to start in a study | The server refuses unless the participant has consented to that study; consent first, then record. |
| Heart rate shown by the model looks flat or wrong | Expected for the current head on some recordings; see [ADDING_A_MODEL_HEAD.md](ADDING_A_MODEL_HEAD.md), "Current honest state". Set `LIVE_HR_SOURCE=rules` to display the rules' estimate. |
| `cargo test` or the Android build fails after pulling | Rebuild from clean (`cargo clean`, `./gradlew clean`); the Android build needs a JDK 17 or newer and the SDK licences accepted ([DEVELOPER_SETUP.md](DEVELOPER_SETUP.md)). |
