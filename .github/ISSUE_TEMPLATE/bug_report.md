---
name: Bug report
about: Something does not work as documented
labels: bug
---

**What happened, and what did you expect?**

**Steps to reproduce**
1.

**Where**
- [ ] Local Docker (`scripts/deploy.sh local`)  [ ] Server (`scripts/deploy.sh vm`)  [ ] Android app  [ ] Console  [ ] Scripts
- Commit or version:
- OS and Docker version (or phone model and Android version):

**Logs**
`scripts/local.sh logs backend`, or `docker compose logs --tail=80 backend` on the server.
Remove tokens, passwords, email addresses and hostnames you do not want public first.
Do not report security problems here; see SECURITY.md.
