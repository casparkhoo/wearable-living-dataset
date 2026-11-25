## What and why

## How it was checked
- [ ] Suites for the parts I touched pass (docs/TESTING.md); CI is green
- [ ] Tried on a device or a local stack, if the change affects them (say which)
- [ ] Docs updated in this pull request; no feature is described that is not in the code
- [ ] Schema change: migration in `backend/migrations/` (safe to re-run) and `backend/schema.sql`
- [ ] Method output can change: `RECIPE_VERSION` or the method version bumped
- [ ] No secrets, `.env` files, instance addresses or personal data
