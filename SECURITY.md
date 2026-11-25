# Security

This system stores physiological data about people. If you find a vulnerability (for
example a way for one participant to read another's stream, or to act on a study without
the right role), please report it privately through GitHub: on the repository, open
**Security > Report a vulnerability**
(<https://github.com/casparkhoo/wearable-living-dataset/security/advisories/new>). Do not open a
public issue or pull request for it. Please allow time for instances to be patched; you will get
an acknowledgement, and credit in the fix if you want it.

Include what you found, how to reproduce it, and which version or commit you tested.

Operators: keep `AUTH_ALLOW_SHARED_TOKEN=false` once all clients sign in, keep `.env` at mode
600, rotate secrets if a server is compromised (`docs/SELF_HOSTING.md`), run a server with
at least 2 GB of RAM, and apply updates promptly. Never commit `.env`, `deploy.env`,
`instance.properties` or keys; `scripts/deploy.sh` generates secrets on the machine they belong to.
