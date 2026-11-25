# Offline tests

No GPU, database or network needed. From the repo root:

- Rust: `cd backend && cargo test`
- Python (unittest, pytest also works if installed): `python -m unittest discover -s scripts/tests -v` and `python -m unittest discover -s ml-inference-server/tests -v`
