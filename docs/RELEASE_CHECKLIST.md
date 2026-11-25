# Release checklist

What remains to do by hand to publish the public repository
(`casparkhoo/wearable-living-dataset`, private until step 5). The export, secrets scan and CI
commands below were already run on a scratch export; repeat them if anything changed.

## Before publishing

1. **Commit everything** in the thesis repository: the export copies tracked files only.
2. **Export and leak check**, into the public checkout:

   ```bash
   scripts/export_public.sh ../wearable-living-dataset    # must end with "leak check passed"
   ```

   The leak check reads the author-specific patterns from `scripts/public_denylist.local`
   (gitignored); keep that file up to date with anything that names your own instance.
3. **Look at the result** in the public checkout: `git status`, `git diff --stat`, and skim the
   file list. Run a secrets scanner if you have one (`gitleaks detect --no-git`); the scratch
   export was scanned with patterns for keys, tokens, private IPs, OCIDs, SSH keys, emails and
   tailnet names, and only placeholders such as `you@example.org` matched.
4. **CI on the exported tree** (what the workflow runs):

   ```bash
   (cd backend && cargo test --locked)
   (cd frontend && npm ci && npm test --if-present && npm run build)
   python -m unittest discover -s scripts/tests && python -m unittest discover -s ml-inference-server/tests
   python scripts/test_processor_contract.py && python scripts/validate_processor_contract.py
   (cd android-application/android && ./gradlew --no-daemon :app:testDebugUnitTest)   # Android: not run in the audit
   ```
5. **PaPaGei submodule**: `export_public.sh` prints the commit; in the public checkout run
   `git submodule update --init` so `.gitmodules` and the pinned commit agree.
6. **Try the quickstart from a fresh clone** of the public repository: `scripts/deploy.sh local --dry-run`,
   then the real thing (README, "Quick start").

## GitHub settings (cannot be done from the repository)

- [ ] **Make the repository public** (Settings, Danger zone).
- [ ] **Enable private vulnerability reporting** (Settings, Code security): SECURITY.md points to it.
- [ ] Branch protection on `master`: require the CI checks.
- [ ] Set a description and topics; confirm the licence shows as MIT.
- [ ] Executable bits: Git on Windows records scripts as `100644`. Fix once in the thesis repository
      with `git update-index --chmod=+x scripts/*.sh` and commit, then re-export. Until then the
      README tells users to run `bash scripts/<name>.sh`.

## Tag and version

- [ ] `CITATION.cff` says `version: "1.0.0"` and `date-released`: update both to the tag you
      create, and keep `backend/Cargo.toml` and `frontend/package.json` consistent with it.
- [ ] Tag and publish the release: `git tag -a v1.0.0 -m "v1.0.0" && git push origin v1.0.0`.
- [ ] (Optional) Archive the release on Zenodo for a DOI, then add the DOI to `CITATION.cff`.

## App and console

- [ ] Build the signed APK: `scripts/release_apk.sh` (signing key stays outside the repository; back
      it up, phones refuse updates signed with another key).
- [ ] Upload the APK and its `.sha256` to the GitHub release or your own host.
- [ ] Set the console host's variables (Vercel project): `VITE_BACKEND_URL`, `VITE_SUPABASE_URL`,
      `VITE_SUPABASE_PUBLISHABLE_KEY`, and `VITE_APK_URL`, `VITE_APK_VERSION`, `VITE_APK_SHA256`,
      `VITE_APK_SIZE_BYTES` (the script prints the last three).
- [ ] Supabase: Site URL and redirect URLs set to the console address.

## After publishing

- [ ] Open the published README on GitHub and click each link; fix broken ones in the thesis
      repository and re-export.
- [ ] Update the thesis report with the public repository link and release tag.
