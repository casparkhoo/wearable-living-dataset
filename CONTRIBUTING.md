# Contributing

Issues and pull requests are welcome, especially new wearable adapters, model workers and
analysis methods. Start with [docs/ADDING_A_DEVICE.md](docs/ADDING_A_DEVICE.md) for a wearable,
[docs/ADDING_A_MODEL_HEAD.md](docs/ADDING_A_MODEL_HEAD.md) for a PaPaGei head and
[docs/EXTENDING.md](docs/EXTENDING.md) for everything else. [docs/DEVELOPER_SETUP.md](docs/DEVELOPER_SETUP.md)
gets each component building, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains the design.
[AGENTS.md](AGENTS.md) is a short, ordered developer guide to the same steps.

## Workflow

1. Open an issue first for anything larger than a small fix, so the approach can be agreed.
2. Branch from `master`, make a focused change, and keep unrelated edits out of it.
3. Run the checks for the parts you touched (below) and add or update tests with the change.
4. Open a pull request that says what changed and why, and how you checked it (which suites, and
   whether you tried it on a device). CI runs the offline suites on every pull request
   ([docs/TESTING.md](docs/TESTING.md)); it must pass.
5. Documentation changes belong in the same pull request as the code that makes them true.

## Rules for changes

- Keep evidence immutable: new behaviour adds derived results with a method and version,
  not new columns on `raw_packets` or `stream_samples`.
- Bump `RECIPE_VERSION` (or your method's version) whenever its output can change.
- A schema change is a migration in `backend/migrations/` that is safe to re-run, mirrored in
  `backend/schema.sql` ([docs/EXTENDING.md](docs/EXTENDING.md), "Change the schema").
- Document what exists. Do not describe a feature that is not in the code, and say plainly when
  something is a pilot or unvalidated (a model head's `validation_status` is part of that).

## Checks before you push

```bash
cd backend && cargo test
cd frontend && npm test && npm run build
cd android-application/android && ./gradlew :app:testDebugUnitTest
python -m unittest discover -s scripts/tests && python -m unittest discover -s ml-inference-server/tests
python scripts/test_processor_contract.py
scripts/test_erasure.sh          # if you touch anything that stores or deletes data (needs Docker)
```

## Commits

- A short imperative or descriptive subject (under about 72 characters), then a body saying why
  when it is not obvious. One logical change per commit.
- Commit locally and review `git status` and `git diff --staged` first. Do not push to `master`
  without agreement from the maintainer: in this project a push to `master` also deploys the
  hosted console.

## Secrets and private data

- Never commit `.env` files, `deploy.env`, `instance.properties`, keys, passwords, tokens,
  broker password files, recordings, database dumps or exports. They are listed in `.gitignore`;
  do not work around it with `git add -f`.
- Instance values (server addresses, Supabase project, instance identifiers, SSH details) come from
  those local files or from environment variables, never from source code, documentation, test
  fixtures or CI configuration. CI needs no secrets and none should be added for the offline suites.
- Use placeholders in documentation and examples (`api.example.org`, `<project-ref>`).
- Participant data does not belong in issues or pull requests either. Describe a problem with
  synthetic data or counts, not recordings.
- Report a vulnerability as described in [SECURITY.md](SECURITY.md), not in a public issue.
